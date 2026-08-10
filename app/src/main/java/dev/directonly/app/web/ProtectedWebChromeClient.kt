package dev.directonly.app.web

import android.net.Uri
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

class ProtectedWebChromeClient(
    private val onFileChooser: (ValueCallback<Array<Uri>>, FileChooserParams) -> Boolean,
    private val onPermission: (PermissionRequest) -> Unit,
    private val onPermissionCanceled: (PermissionRequest) -> Unit,
    private val onShowFullscreen: (View, CustomViewCallback) -> Unit,
    private val onHideFullscreen: () -> Unit,
) : WebChromeClient() {
    private var activePermissionRequest: PermissionRequest? = null

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = onFileChooser(filePathCallback, fileChooserParams)

    override fun onPermissionRequest(request: PermissionRequest) {
        activePermissionRequest?.deny()
        activePermissionRequest = request
        onPermission(request)
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        if (activePermissionRequest === request) activePermissionRequest = null
        onPermissionCanceled(request)
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) =
        onShowFullscreen(view, callback)

    override fun onHideCustomView() = onHideFullscreen()

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        callback?.invoke(origin, false, false)
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?,
    ): Boolean = false

    fun clearActivePermissionRequest(request: PermissionRequest? = null) {
        val active = activePermissionRequest ?: return
        if (request == null || request === active) {
            active.deny()
            activePermissionRequest = null
        }
    }

    fun completePermissionRequest(request: PermissionRequest) {
        if (activePermissionRequest === request) activePermissionRequest = null
    }
}
