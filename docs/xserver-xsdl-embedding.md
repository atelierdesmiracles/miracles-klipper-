# Experimental XServer-XSDL embedding

This branch is an isolated integration experiment. Miracle Klipper does not yet bundle XServer-XSDL in its APK.

## First milestone: reproducible upstream build

The manual GitHub Actions workflow `Experimental embedded XServer-XSDL build` builds the upstream XServer-XSDL application using the toolchain documented by its upstream project and archives any generated APK/native outputs and the source revision.

Upstream build source:
- https://github.com/pelya/xserver-xsdl
- https://github.com/pelya/commandergenius/tree/sdl_android/project/jni/application/xserver

## Integration constraints

XServer-XSDL is an Android application built around a patched SDL 1.2 port and native X server components. Copying an APK or a shared library into Miracle Klipper is not sufficient to embed it. A later milestone must port the required Android Java activity/service, resources, native libraries, startup lifecycle and data assets into the Miracle Klipper application, resolve package/resource conflicts, and build/test all four ABIs (armeabi-v7a, arm64-v8a, x86, x86_64).

Until those tasks are completed and tested, the existing external XServer-XSDL launch path must remain available as a fallback. Do not describe this experiment as an embedded X server yet.

## Licensing

Preserve upstream copyright and license notices for XServer-XSDL, SDL, X.Org, and all bundled dependencies. Review the complete license set before redistribution.
