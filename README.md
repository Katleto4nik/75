# MekaOptimization

optimize second futures with Minecraft

Fabric mod for Minecraft 1.21.11 (Java 21). Always on, no toggle. Legit mode: human-like delays, only clicks what the crosshair is really on. No rendering / OpenGL code, so it works with Vulkan renderers too.

## Build on GitHub
1. Upload all files of this folder to a GitHub repository (the `.github` folder must be there).
2. Open the **Actions** tab -> **build** -> latest run -> **Artifacts** -> download `MekaOptimization`.
3. Put the jar into `mods/` together with Fabric API.

If the `.github` folder does not upload: GitHub -> Add file -> Create new file -> name it
`.github/workflows/build.yml` and paste the content of `build.yml` from the root of this project.
