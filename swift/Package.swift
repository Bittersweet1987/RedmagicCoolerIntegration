// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "RedMagicCoolerLib",
    platforms: [
        .iOS(.v13),
        .macOS(.v11)
    ],
    products: [
        .library(
            name: "RedMagicCoolerLib",
            targets: ["RedMagicCoolerLib"]
        )
    ],
    targets: [
        .target(
            name: "RedMagicCoolerLib",
            path: "Sources/RedMagicCoolerLib"
        )
    ]
)
