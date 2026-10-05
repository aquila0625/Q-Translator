package com.yishulabs.qtranslator.update

import androidx.core.content.FileProvider

/** 应用内更新专用的 FileProvider（和相机用的 .files 分开，清单里不能有两个同名组件） */
class UpdateFileProvider : FileProvider()
