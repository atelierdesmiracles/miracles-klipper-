package com.octo4a.repository

import org.junit.Assert.assertThrows
import org.junit.Test

class LinuxEnvironmentManifestTest {
    @Test
    fun acceptsPinnedDebianArm64Manifest() {
        validManifest().validate()
    }

    @Test
    fun acceptsPinnedX86_64DebianManifest() {
        val base = validManifest()
        val manifest = base.copy(
            distribution = LinuxDistribution("Debian", "13", "trixie", "x86_64", "x86_64"),
            rootfs = base.rootfs.copy(
                url = "https://github.com/termux/proot-distro/releases/download/v4.26.0/debian-trixie-x86_64-pd-v4.26.0.tar.xz"
            )
        )
        manifest.validate()
    }

    @Test
    fun rejectsUnpinnedRootfsChecksum() {
        val manifest = validManifest()
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(rootfs = manifest.rootfs.copy(sha256 = "latest")).validate()
        }
    }

    @Test
    fun rejectsPackageNamesThatCouldBecomeCommandArguments() {
        val manifest = validManifest()
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(
                packages = manifest.packages.copy(foundation = listOf("python3", ";rm -rf /"))
            ).validate()
        }
    }

    private fun validManifest() = LinuxEnvironmentManifest(
        schemaVersion = 1,
        distribution = LinuxDistribution("Debian", "13", "trixie", "arm64", "arm64-v8a"),
        rootfs = LinuxRootfs(
            source = "termux/proot-distro",
            sourceRelease = "v4.26.0",
            url = "https://github.com/termux/proot-distro/releases/download/v4.26.0/debian-trixie-aarch64-pd-v4.26.0.tar.xz",
            sha256 = "a".repeat(64),
            archiveFormat = "tar.xz",
            stripComponents = 1
        ),
        aptSnapshot = AptSnapshot(
            date = "2026-10-21",
            debian = "https://snapshot.debian.org/archive/debian/20261021T000000Z/",
            security = "https://snapshot.debian.org/archive/debian-security/20261021T000000Z/",
            suite = "trixie",
            securitySuite = "trixie-security",
            component = "main"
        ),
        packages = LinuxPackages(
            foundation = listOf("ca-certificates", "python3", "python3-venv"),
            klipperMcuToolchain = listOf("gcc-avr", "avr-libc")
        ),
        minimumFreeStorageBytes = 1_610_612_736L
    )
}
