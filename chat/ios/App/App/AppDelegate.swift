import UIKit
import Capacitor

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Override point for customization after application launch.
        return true
    }

    func applicationWillResignActive(_ application: UIApplication) {
        // Sent when the application is about to move from active to inactive state. This can occur for certain types of temporary interruptions (such as an incoming phone call or SMS message) or when the user quits the application and it begins the transition to the background state.
        // Use this method to pause ongoing tasks, disable timers, and invalidate graphics rendering callbacks. Games should use this method to pause the game.
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        // Use this method to release shared resources, save user data, invalidate timers, and store enough application state information to restore your application to its current state in case it is terminated later.
        // If your application supports background execution, this method is called instead of applicationWillTerminate: when the user quits.
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        // Called as part of the transition from the background to the active state; here you can undo many of the changes made on entering the background.
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        // Restart any tasks that were paused (or not yet started) while the application was inactive. If the application was previously in the background, optionally refresh the user interface.
    }

    func applicationWillTerminate(_ application: UIApplication) {
        // Called when the application is about to terminate. Save data if appropriate. See also applicationDidEnterBackground:.
    }

    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        // Called when the app was launched with a url. Feel free to add additional processing here,
        // but if you want the App API to support tracking app url opens, make sure to keep this call
        return ApplicationDelegateProxy.shared.application(app, open: url, options: options)
    }

    func application(_ application: UIApplication, continue userActivity: NSUserActivity, restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void) -> Bool {
        // Called when the app was launched with an activity, including Universal Links.
        // Feel free to add additional processing here, but if you want the App API to support
        // tracking app url opens, make sure to keep this call
        return ApplicationDelegateProxy.shared.application(application, continue: userActivity, restorationHandler: restorationHandler)
    }

}

// MARK: - 点滴集：截图记账

import Photos
import Vision

/// 主界面：注册本地插件（写在 AppDelegate.swift 里，免得改 Xcode 工程文件列表）
class MainViewController: CAPBridgeViewController {
    override func capacitorDidLoad() {
        bridge?.registerPluginInstance(RecentScreenshotPlugin())
    }
}

/// 像微信「你可能要发送的照片」：找最近 N 秒内的截图；识别用 Vision 在本机完成，图片不出设备。
@objc(RecentScreenshotPlugin)
public class RecentScreenshotPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "RecentScreenshotPlugin"
    public let jsName = "RecentScreenshot"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "latest", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "recognize", returnType: CAPPluginReturnPromise),
    ]

    private func withAccess(_ call: CAPPluginCall, _ body: @escaping () -> Void) {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        switch status {
        case .authorized, .limited:
            body()
        case .notDetermined:
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { s in
                if s == .authorized || s == .limited { body() } else { call.resolve(["found": false, "denied": true]) }
            }
        default:
            call.resolve(["found": false, "denied": true])
        }
    }

    @objc func latest(_ call: CAPPluginCall) {
        let within = call.getDouble("withinSeconds") ?? 60
        withAccess(call) {
            let opts = PHFetchOptions()
            opts.predicate = NSPredicate(
                format: "(mediaSubtypes & %d) != 0 AND creationDate > %@",
                PHAssetMediaSubtype.photoScreenshot.rawValue,
                Date().addingTimeInterval(-within) as NSDate
            )
            opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
            opts.fetchLimit = 1
            guard let asset = PHAsset.fetchAssets(with: .image, options: opts).firstObject else {
                call.resolve(["found": false]); return
            }
            self.image(for: asset, maxSide: 360) { img in
                var ret: [String: Any] = [
                    "found": true,
                    "id": asset.localIdentifier,
                    "createdAt": (asset.creationDate?.timeIntervalSince1970 ?? 0) * 1000,
                ]
                if let data = img?.jpegData(compressionQuality: 0.6) {
                    ret["thumbnail"] = "data:image/jpeg;base64," + data.base64EncodedString()
                }
                call.resolve(ret)
            }
        }
    }

    @objc func recognize(_ call: CAPPluginCall) {
        guard let id = call.getString("id"),
              let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else {
            call.reject("截图不存在"); return
        }
        image(for: asset, maxSide: 2400) { img in
            guard let cg = img?.cgImage else { call.reject("读取截图失败"); return }
            let req = VNRecognizeTextRequest { req, err in
                if let err = err { call.reject(err.localizedDescription); return }
                let obs = (req.results as? [VNRecognizedTextObservation]) ?? []
                call.resolve(["lines": Self.mergeRows(obs)])
            }
            req.recognitionLevel = .accurate
            req.recognitionLanguages = ["zh-Hans", "en-US"]
            req.usesLanguageCorrection = true
            DispatchQueue.global(qos: .userInitiated).async {
                do { try VNImageRequestHandler(cgImage: cg).perform([req]) } catch { call.reject(error.localizedDescription) }
            }
        }
    }

    /// 同一行的文字块（如「收款方 ……… 老乡鸡」）合并成一行，从上到下输出
    private static func mergeRows(_ obs: [VNRecognizedTextObservation]) -> [String] {
        let items = obs.compactMap { o -> (y: CGFloat, x: CGFloat, h: CGFloat, text: String)? in
            guard let t = o.topCandidates(1).first?.string else { return nil }
            return (o.boundingBox.midY, o.boundingBox.minX, o.boundingBox.height, t)
        }.sorted { $0.y > $1.y }
        var rows: [[(y: CGFloat, x: CGFloat, h: CGFloat, text: String)]] = []
        for it in items {
            if let last = rows.last?.first, abs(last.y - it.y) < max(last.h, it.h) * 0.5 {
                rows[rows.count - 1].append(it)
            } else {
                rows.append([it])
            }
        }
        return rows.map { $0.sorted { $0.x < $1.x }.map(\.text).joined(separator: " ") }
    }

    private func image(for asset: PHAsset, maxSide: CGFloat, done: @escaping (UIImage?) -> Void) {
        let opts = PHImageRequestOptions()
        opts.deliveryMode = .highQualityFormat
        opts.isNetworkAccessAllowed = true
        let scale = min(1, maxSide / CGFloat(max(asset.pixelWidth, asset.pixelHeight)))
        let size = CGSize(width: CGFloat(asset.pixelWidth) * scale, height: CGFloat(asset.pixelHeight) * scale)
        PHImageManager.default().requestImage(for: asset, targetSize: size, contentMode: .aspectFit, options: opts) { img, _ in
            done(img)
        }
    }
}
