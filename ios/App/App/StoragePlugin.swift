import Foundation
import Capacitor
import UIKit

@objc(StoragePlugin)
public class StoragePlugin: CAPPlugin, UIDocumentPickerDelegate {
    
    private let PREFS_NAME = "cloud_storage_app"
    private let PREF_SELECTED_URL = "selected_url"
    private let PREF_SHARE_CODE = "share_code"
    private let PREF_RELAY_BASE_URL = "relay_base_url"
    
    private var isNodeRunning = false
    
    @objc func getInitialState(_ call: CAPPluginCall) {
        let defaults = UserDefaults.standard
        let shareCode = defaults.string(forKey: PREF_SHARE_CODE) ?? generateShareCode()
        let relayUrl = defaults.string(forKey: PREF_RELAY_BASE_URL) ?? ""
        let selectedUrl = defaults.string(forKey: PREF_SELECTED_URL) ?? ""
        
        let state: [String: Any] = [
            "folderName": resolveFolderName(selectedUrl),
            "shareCode": shareCode,
            "relayBaseUrl": relayUrl,
            "isNodeRunning": isNodeRunning,
            "storageUsed": 10, // Mocked for now
            "storageTotal": 100,
            "usagePercent": 10
        ]
        call.resolve(state)
    }
    
    @objc func selectFolder(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder])
            picker.delegate = self
            picker.allowsMultipleSelection = false
            self.bridge?.viewController?.present(picker, animated: true)
        }
        call.resolve()
    }
    
    @objc func toggleNode(_ call: CAPPluginCall) {
        isNodeRunning.toggle()
        notifyStateChange()
        call.resolve()
    }
    
    @objc func shareInvite(_ call: CAPPluginCall) {
        let text = "Join my Easy Storage Cloud!"
        DispatchQueue.main.async {
            let ac = UIActivityViewController(activityItems: [text], applicationActivities: nil)
            self.bridge?.viewController?.present(ac, animated: true)
        }
        call.resolve()
    }
    
    @objc func copyToClipboard(_ call: CAPPluginCall) {
        let text = call.getString("text") ?? ""
        UIPasteboard.general.string = text
        call.resolve()
    }
    
    @objc func updateRelayBaseUrl(_ call: CAPPluginCall) {
        let url = call.getString("url") ?? ""
        UserDefaults.standard.set(url, forKey: PREF_RELAY_BASE_URL)
        notifyStateChange()
        call.resolve()
    }
    
    // MARK: - Helpers
    
    private func generateShareCode() -> String {
        let code = String(format: "%06d", arc4random_uniform(1000000))
        UserDefaults.standard.set(code, forKey: PREF_SHARE_CODE)
        return code
    }
    
    private func resolveFolderName(_ urlString: String) -> String? {
        guard let url = URL(string: urlString) else { return nil }
        return url.lastPathComponent
    }
    
    private func notifyStateChange() {
        // Echo state back to JS
        let defaults = UserDefaults.standard
        let shareCode = defaults.string(forKey: PREF_SHARE_CODE) ?? ""
        let relayUrl = defaults.string(forKey: PREF_RELAY_BASE_URL) ?? ""
        
        let state: [String: Any] = [
            "folderName": "iOS Drive", // Mocked
            "shareCode": shareCode,
            "relayBaseUrl": relayUrl,
            "isNodeRunning": isNodeRunning,
            "storageUsed": 20,
            "storageTotal": 128,
            "usagePercent": 15
        ]
        
        if let jsonData = try? JSONSerialization.data(withJSONObject: state),
           let jsonString = String(data: jsonData, encoding: .utf8) {
            self.bridge?.eval(js: "window.updateWebState?.('\(jsonString)');")
        }
    }
    
    // MARK: - UIDocumentPickerDelegate
    
    public func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return }
        UserDefaults.standard.set(url.absoluteString, forKey: PREF_SELECTED_URL)
        notifyStateChange()
    }
}
