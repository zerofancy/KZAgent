package com.kzagent.kagent.desktop

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import java.nio.file.Path

internal suspend fun chooseWorkspace(current: Path): Path? =
    FileKit.openDirectoryPicker(directory = PlatformFile(current.toFile()))
        ?.file?.toPath()?.toAbsolutePath()?.normalize()
