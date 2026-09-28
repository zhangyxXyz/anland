package com.anland.design

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

/** A real settings destination, using the template's searchable language page. */
class LanguageActivity: AppCompatActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState:Bundle?) {
        applySavedAppearance(this)
        super.onCreate(savedInstanceState)
        setContent { WithAnlandTheme {
            Scaffold(containerColor=MaterialTheme.colorScheme.surface,topBar={
                TopAppBar(title={Text(stringResource(R.string.settings_language))},
                    navigationIcon={IconButton(onClick={finish()}) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.design_back))}},
                    colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface))
            }) { padding -> Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter) {Box(Modifier.widthIn(max=920.dp).fillMaxSize()){LanguageSettingsScreen()}} }
        } }
    }
}
