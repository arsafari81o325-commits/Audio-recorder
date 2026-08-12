package com.example.audiorecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts

class PermissionHelper(private val activity: AppCompatActivity) {

    private var onRecordAudioResult: ((Boolean) -> Unit)? = null
    private var onNotificationResult: ((Boolean) -> Unit)? = null

    private val recordAudioLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val callback = onRecordAudioResult
        onRecordAudioResult = null
        if (isGranted) {
            callback?.invoke(true)
        } else {
            val permanentlyDenied = !activity.shouldShowRequestPermissionRationale(
                Manifest.permission.RECORD_AUDIO
            )
            if (permanentlyDenied) {
                showPermanentlyDeniedDialog(
                    "مجوز میکروفون",
                    "بدون این مجوز امکان ضبط صدا وجود ندارد. لطفاً از تنظیمات اپ، مجوز میکروفون را فعال کنید."
                ) { callback?.invoke(false) }
            } else {
                callback?.invoke(false)
            }
        }
    }

    private val notificationLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val callback = onNotificationResult
        onNotificationResult = null
        callback?.invoke(isGranted)
    }

    fun hasRecordAudio(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                activity, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun requestRecordAudio(onResult: (Boolean) -> Unit) {
        if (hasRecordAudio()) {
            onResult(true)
            return
        }
        onRecordAudioResult = onResult
        if (activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            showRationaleDialog(
                title = "مجوز دسترسی به صدا",
                message = "این اپ برای ضبط صدای داخلی دستگاه (مثلاً پخش ویدیو یا موسیقی) به مجوز صوتی Android نیاز دارد. " +
                        "توجه: این اپ صدای میکروفون شما را ضبط نمی‌کند، بلکه خروجی صوتی دستگاه را ثبت می‌کند.",
                onConfirm = { recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onCancel = { onResult(false); onRecordAudioResult = null }
            )
        } else {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun requestNotificationPermission(onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasNotificationPermission()) {
            onResult(true)
            return
        }
        onNotificationResult = onResult
        if (activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            showRationaleDialog(
                title = "مجوز اعلان‌ها",
                message = "برای نمایش دکمه‌های کنترل ضبط در نوار اعلان‌ها، این مجوز لازم است.",
                onConfirm = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                onCancel = { onResult(false); onNotificationResult = null }
            )
        } else {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        activity.startActivity(intent)
    }

    private fun showRationaleDialog(
        title: String,
        message: String,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("ادامه") { _, _ -> onConfirm() }
            .setNegativeButton("انصراف") { _, _ -> onCancel() }
            .setCancelable(false)
            .show()
    }

    private fun showPermanentlyDeniedDialog(title: String, message: String, onDismiss: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("باز کردن تنظیمات") { _, _ -> openAppSettings() }
            .setNegativeButton("بعداً") { _, _ -> onDismiss() }
            .setOnDismissListener { onDismiss() }
            .show()
    }
}
