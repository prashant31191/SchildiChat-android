/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.riotx.features.widgets

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.view.forEach
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import com.airbnb.mvrx.activityViewModel
import com.airbnb.mvrx.args
import com.airbnb.mvrx.withState
import im.vector.matrix.android.api.session.terms.TermsService
import im.vector.riotx.R
import im.vector.riotx.core.platform.OnBackPressed
import im.vector.riotx.core.platform.VectorBaseFragment
import im.vector.riotx.core.utils.openUrlInExternalBrowser
import im.vector.riotx.features.terms.ReviewTermsActivity
import im.vector.riotx.features.webview.WebViewEventListener
import im.vector.riotx.features.widgets.webview.clearAfterWidget
import im.vector.riotx.features.widgets.webview.setupForWidget
import kotlinx.android.parcel.Parcelize
import kotlinx.android.synthetic.main.fragment_room_widget.*
import timber.log.Timber
import javax.inject.Inject

@Parcelize
data class WidgetArgs(
        val baseUrl: String,
        val kind: WidgetKind,
        val roomId: String,
        val widgetId: String? = null,
        val urlParams: Map<String, String> = emptyMap()
) : Parcelable

class WidgetFragment @Inject constructor() : VectorBaseFragment(), WebViewEventListener, OnBackPressed {

    private val fragmentArgs: WidgetArgs by args()
    private val viewModel: WidgetViewModel by activityViewModel()

    override fun getLayoutResId() = R.layout.fragment_room_widget

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
        widgetWebView.setupForWidget(this)
        if (fragmentArgs.kind.isAdmin()) {
            viewModel.getPostAPIMediator().setWebView(widgetWebView)
        }
        viewModel.observeViewEvents {
            when (it) {
                is WidgetViewEvents.DisplayTerms              -> displayTerms(it)
                is WidgetViewEvents.LoadFormattedURL          -> loadFormattedUrl(it)
                is WidgetViewEvents.DisplayIntegrationManager -> displayIntegrationManager(it)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == ReviewTermsActivity.TERMS_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                viewModel.handle(WidgetAction.OnTermsReviewed)
            } else {
                vectorBaseActivity.finish()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (fragmentArgs.kind.isAdmin()) {
            viewModel.getPostAPIMediator().clearWebView()
        }
        widgetWebView.clearAfterWidget()
    }

    override fun onResume() {
        super.onResume()
        widgetWebView?.let {
            it.resumeTimers()
            it.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        widgetWebView?.let {
            it.pauseTimers()
            it.onPause()
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) = withState(viewModel) { state ->
        val widget = state.asyncWidget()
        menu.findItem(R.id.action_edit)?.isVisible = state.widgetKind != WidgetKind.INTEGRATION_MANAGER
        if (widget == null) {
            menu.findItem(R.id.action_refresh)?.isVisible = false
            menu.findItem(R.id.action_widget_open_ext)?.isVisible = false
            menu.findItem(R.id.action_delete)?.isVisible = false
            menu.findItem(R.id.action_revoke)?.isVisible = false
        } else {
            menu.findItem(R.id.action_refresh)?.isVisible = true
            menu.findItem(R.id.action_widget_open_ext)?.isVisible = true
            menu.findItem(R.id.action_delete)?.isVisible = state.canManageWidgets && widget.isAddedByMe
            menu.findItem(R.id.action_revoke)?.isVisible = state.status == WidgetStatus.WIDGET_ALLOWED && !widget.isAddedByMe
        }
        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = withState(viewModel) { state ->
        when (item.itemId) {
            R.id.action_edit            -> {
                navigator.openIntegrationManager(requireContext(), state.roomId, state.widgetId, state.widgetKind.screenId)
                return@withState true
            }
            R.id.action_delete          -> {
                viewModel.handle(WidgetAction.DeleteWidget)
                return@withState true
            }
            R.id.action_refresh         -> if (state.formattedURL.complete) {
                widgetWebView.reload()
                return@withState true
            }
            R.id.action_widget_open_ext -> if (state.formattedURL.complete) {
                openUrlInExternalBrowser(requireContext(), state.formattedURL.invoke())
                return@withState true
            }
            R.id.action_revoke          -> if (state.status == WidgetStatus.WIDGET_ALLOWED) {
                viewModel.handle(WidgetAction.RevokeWidget)
                return@withState true
            }
        }
        return@withState super.onOptionsItemSelected(item)
    }

    override fun onBackPressed(toolbarButton: Boolean): Boolean = withState(viewModel) { state ->
        if (state.formattedURL.complete) {
            if (widgetWebView.canGoBack()) {
                widgetWebView.goBack()
                return@withState true
            }
        }
        return@withState false
    }

    override fun invalidate() = withState(viewModel) { state ->
        Timber.v("Invalidate state: $state")
        when (state.status) {
            WidgetStatus.UNKNOWN            -> {
                //Hide all?
                widgetWebView.isVisible = false
            }
            WidgetStatus.WIDGET_NOT_ALLOWED -> {
                widgetWebView.isVisible = false
            }
            WidgetStatus.WIDGET_ALLOWED     -> {
                widgetWebView.isVisible = true
                when (state.formattedURL) {
                    Uninitialized -> {
                    }
                    is Loading    -> {
                        setStateError(null)
                        widgetProgressBar.isIndeterminate = true
                        widgetProgressBar.isVisible = true
                    }
                    is Success    -> {
                        setStateError(null)
                        when (state.webviewLoadedUrl) {
                            Uninitialized -> {
                                widgetWebView.isInvisible = true
                            }
                            is Loading    -> {
                                setStateError(null)
                                widgetWebView.isInvisible = false
                                widgetProgressBar.isIndeterminate = true
                                widgetProgressBar.isVisible = true
                            }
                            is Success    -> {
                                widgetWebView.isInvisible = false
                                widgetProgressBar.isVisible = false
                                setStateError(null)
                            }
                            is Fail       -> {
                                widgetProgressBar.isInvisible = true
                                setStateError(state.webviewLoadedUrl.error.message)
                            }
                        }
                    }
                    is Fail       -> {
                        //we need to show Error
                        widgetWebView.isInvisible = true
                        widgetProgressBar.isVisible = false
                        setStateError(state.formattedURL.error.message)
                    }
                }
            }
        }
    }

    override fun onPageStarted(url: String) {
        viewModel.handle(WidgetAction.OnWebViewStartedToLoad(url))
    }

    override fun onPageFinished(url: String) {
        viewModel.handle(WidgetAction.OnWebViewLoadingSuccess(url))
    }

    override fun onPageError(url: String, errorCode: Int, description: String) {
        viewModel.handle(WidgetAction.OnWebViewLoadingError(url, false, errorCode, description))
    }

    override fun onHttpError(url: String, errorCode: Int, description: String) {
        viewModel.handle(WidgetAction.OnWebViewLoadingError(url, true, errorCode, description))
    }

    private fun displayTerms(displayTerms: WidgetViewEvents.DisplayTerms) {
        navigator.openTerms(
                fragment = this,
                serviceType = TermsService.ServiceType.IntegrationManager,
                baseUrl = displayTerms.url,
                token = displayTerms.token
        )
    }

    private fun loadFormattedUrl(loadFormattedUrl: WidgetViewEvents.LoadFormattedURL) {
        widgetWebView.clearHistory()
        widgetWebView.loadUrl(loadFormattedUrl.formattedURL)
    }

    private fun setStateError(message: String?) {
        if (message == null) {
            widgetErrorLayout.isVisible = false
            widgetErrorText.text = null
        } else {
            widgetProgressBar.isVisible = false
            widgetErrorLayout.isVisible = true
            widgetWebView.isInvisible = true
            widgetErrorText.text = getString(R.string.room_widget_failed_to_load, message)
        }
    }

    private fun displayIntegrationManager(event: WidgetViewEvents.DisplayIntegrationManager) {
        navigator.openIntegrationManager(
                context = vectorBaseActivity,
                roomId = fragmentArgs.roomId,
                integId = event.integId,
                screen = event.integType
        )
    }

    fun deleteWidget() {
        AlertDialog.Builder(requireContext())
                .setMessage(R.string.widget_delete_message_confirmation)
                .setPositiveButton(R.string.remove) { _, _ ->
                    viewModel.handle(WidgetAction.DeleteWidget)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    fun revokeWidget() {
        viewModel.handle(WidgetAction.RevokeWidget)
    }
}
