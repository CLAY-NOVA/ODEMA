// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "RainKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "RainKit", targets: ["RainKit"]),
    ],
    targets: [
        .target(name: "RainKit"),
        .testTarget(
            name: "RainKitTests",
            dependencies: ["RainKit"],
            resources: [.copy("Fixtures")]
        ),
    ]
)
