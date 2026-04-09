package com.backrecorderapp.ui.disclaimer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.backrecorderapp.data.preferences.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DisclaimerViewModel @Inject constructor(
    private val appPreferences: AppPreferences
) : ViewModel() {

    val disclaimerAccepted = appPreferences.disclaimerAccepted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun acceptDisclaimer() {
        viewModelScope.launch {
            appPreferences.setDisclaimerAccepted(true)
        }
    }
}
