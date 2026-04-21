export interface ArchiveSourceFile {
  path: string;
  size: number;
  lastModified: number;
  stream: () => ReadableStream<Uint8Array>;
}

export interface StreamedTarArchive {
  stream: ReadableStream<Uint8Array>;
  byteLength: number;
  fileCount: number;
  directoryCount: number;
}

const TAR_BLOCK_SIZE = 512;
const textEncoder = new TextEncoder();

interface NormalizedArchiveFile extends ArchiveSourceFile {
  path: string;
}

interface TarPathParts {
  name: string;
  prefix: string;
}

export function filesToArchiveEntries(files: File[]): ArchiveSourceFile[] {
  return files.map((file) => {
    const fallbackPath = file.name;
    const relativePath =
      typeof file.webkitRelativePath === 'string' && file.webkitRelativePath.trim().length > 0
        ? file.webkitRelativePath
        : fallbackPath;

    return {
      path: relativePath,
      size: file.size,
      lastModified: file.lastModified,
      stream: () => file.stream(),
    };
  });
}

export function createTarArchiveStream(entries: ArchiveSourceFile[]): StreamedTarArchive {
  const normalizedFiles = normalizeEntries(entries);
  const directories = collectImplicitDirectories(normalizedFiles.map((entry) => entry.path));
  const directoryList = [...directories].sort((left, right) => left.localeCompare(right));

  const byteLength =
    directoryList.length * TAR_BLOCK_SIZE +
    normalizedFiles.reduce((total, entry) => {
      const padding = getTarPadding(entry.size);
      return total + TAR_BLOCK_SIZE + entry.size + padding;
    }, 0) +
    TAR_BLOCK_SIZE * 2;

  const stream = iteratorToStream(generateTar(normalizedFiles, directoryList));

  return {
    stream,
    byteLength,
    fileCount: normalizedFiles.length,
    directoryCount: directoryList.length,
  };
}

function normalizeEntries(entries: ArchiveSourceFile[]): NormalizedArchiveFile[] {
  return entries
    .map((entry) => ({
      ...entry,
      path: normalizeArchivePath(entry.path),
    }))
    .sort((left, right) => left.path.localeCompare(right.path));
}

function normalizeArchivePath(path: string): string {
  const normalized = path.replace(/\\/g, '/').replace(/^\/+/, '').replace(/\/+/g, '/').trim();
  if (!normalized) {
    throw new Error('Archive entry path cannot be empty.');
  }

  const segments = normalized.split('/').filter(Boolean);
  if (segments.some((segment) => segment === '.' || segment === '..')) {
    throw new Error(`Unsafe archive path rejected: ${path}`);
  }

  const cleaned = segments.join('/');
  ensureTarPathFits(cleaned);
  return cleaned;
}

function collectImplicitDirectories(paths: string[]): Set<string> {
  const directories = new Set<string>();
  for (const path of paths) {
    const segments = path.split('/');
    for (let index = 1; index < segments.length; index += 1) {
      const directoryPath = `${segments.slice(0, index).join('/')}/`;
      ensureTarPathFits(directoryPath);
      directories.add(directoryPath);
    }
  }
  return directories;
}

function ensureTarPathFits(path: string) {
  splitTarPath(path);
}

function splitTarPath(path: string): TarPathParts {
  const rawBytes = textEncoder.encode(path);
  if (rawBytes.length <= 100) {
    return { name: path, prefix: '' };
  }

  for (let index = path.lastIndexOf('/'); index > 0; index = path.lastIndexOf('/', index - 1)) {
    const prefix = path.slice(0, index);
    const name = path.slice(index + 1);
    if (textEncoder.encode(prefix).length <= 155 && textEncoder.encode(name).length <= 100) {
      return { name, prefix };
    }
  }

  throw new Error(`Path is too long for streamed TAR upload: ${path}`);
}

async function* generateTar(
  files: NormalizedArchiveFile[],
  directories: string[],
): AsyncGenerator<Uint8Array, void, void> {
  for (const directoryPath of directories) {
    yield createHeader(directoryPath, 0, Math.floor(Date.now() / 1000), '5');
  }

  for (const entry of files) {
    yield createHeader(entry.path, entry.size, Math.floor(entry.lastModified / 1000), '0');

    const reader = entry.stream().getReader();
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) {
          break;
        }
        if (value && value.byteLength > 0) {
          yield value;
        }
      }
    } finally {
      reader.releaseLock();
    }

    const padding = getTarPadding(entry.size);
    if (padding > 0) {
      yield new Uint8Array(padding);
    }
  }

  yield new Uint8Array(TAR_BLOCK_SIZE * 2);
}

function createHeader(path: string, size: number, mtimeSeconds: number, typeFlag: '0' | '5'): Uint8Array {
  const header = new Uint8Array(TAR_BLOCK_SIZE);
  header.fill(0);

  const { name, prefix } = splitTarPath(path);
  writeString(header, 0, 100, name);
  writeOctal(header, 100, 8, typeFlag === '5' ? 0o755 : 0o644);
  writeOctal(header, 108, 8, 0);
  writeOctal(header, 116, 8, 0);
  writeOctal(header, 124, 12, typeFlag === '5' ? 0 : size);
  writeOctal(header, 136, 12, Math.max(0, mtimeSeconds));

  for (let index = 148; index < 156; index += 1) {
    header[index] = 0x20;
  }

  header[156] = typeFlag.charCodeAt(0);
  writeString(header, 157, 100, '');
  writeString(header, 257, 6, 'ustar');
  header[262] = 0;
  writeString(header, 263, 2, '00');
  writeString(header, 265, 32, 'easy-storage');
  writeString(header, 297, 32, 'easy-storage');
  writeString(header, 345, 155, prefix);

  const checksum = header.reduce((sum, value) => sum + value, 0);
  writeChecksum(header, checksum);
  return header;
}

function writeString(target: Uint8Array, offset: number, length: number, value: string) {
  const encoded = textEncoder.encode(value);
  if (encoded.length > length) {
    throw new Error(`TAR field overflow for value: ${value}`);
  }
  target.set(encoded, offset);
}

function writeOctal(target: Uint8Array, offset: number, length: number, value: number) {
  const octal = Math.max(0, Math.floor(value)).toString(8).padStart(length - 1, '0');
  writeString(target, offset, length, `${octal}\0`);
}

function writeChecksum(target: Uint8Array, checksum: number) {
  const octal = checksum.toString(8).padStart(6, '0');
  writeString(target, 148, 8, `${octal}\0 `);
}

function getTarPadding(size: number): number {
  const remainder = size % TAR_BLOCK_SIZE;
  return remainder === 0 ? 0 : TAR_BLOCK_SIZE - remainder;
}

function iteratorToStream(iterator: AsyncGenerator<Uint8Array, void, void>): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      const { done, value } = await iterator.next();
      if (done) {
        controller.close();
        return;
      }
      controller.enqueue(value);
    },
    async cancel(reason) {
      await iterator.throw?.(reason);
    },
  });
}
