package com.omnillm.ui

/**
 * UI process entry module.
 *
 * INV-001: never load native engines or write DB/model store from this process.
 * Talk only to Admin facade / feature view models via [com.omnillm.ui.admin.AdminRuntimeConnection]
 * and [com.omnillm.ui.session.UiSession.attachFeatureViewModels].
 *
 * Primary Compose work areas live under [com.omnillm.ui.screens] (UX-IA tree).
 */
object AppUiModule {
    const val MODULE_PATH: String = ":android:app-ui"
}
