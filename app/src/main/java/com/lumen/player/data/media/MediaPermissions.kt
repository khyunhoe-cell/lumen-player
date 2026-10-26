package com.lumen.player.data.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** 기기 영상·음악을 읽는 데 필요한 권한. 온보딩과 라이브러리가 같은 목록을 쓴다. */
object MediaPermissions {
    val media: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /**
     * 하나라도 있으면 읽을 수 있는 만큼 보여 준다. Android 14의 "선택한 항목만 허용"도 포함한다.
     * Android 13 이상 MediaStore는 권한이 없어도 예외 없이 빈 목록을 돌려주므로 결과만으로는 알 수 없다.
     */
    fun hasAny(context: Context): Boolean {
        val all = if (Build.VERSION.SDK_INT >= 34) media + Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED else media
        return all.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
}
