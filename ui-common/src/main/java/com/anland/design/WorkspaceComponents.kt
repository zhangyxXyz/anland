package com.anland.design

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Common visual language for both apps; feature actions stay with their owner. */
@Composable
fun WorkspaceSearch(value:String,onChange:(String)->Unit,hint:String,modifier:Modifier=Modifier) {
    OutlinedTextField(value,onChange,modifier,singleLine=true,shape=RoundedCornerShape(18.dp),
        placeholder={Text(hint)},leadingIcon={Icon(Icons.Outlined.Search,null)},
        trailingIcon={if(value.isNotEmpty())IconButton(onClick={onChange("")}){Icon(Icons.Outlined.Close,stringResource(R.string.design_clear_search))}})
}

@Composable
fun StatusPill(text:String,modifier:Modifier=Modifier,active:Boolean=true) {
    Surface(modifier,shape=RoundedCornerShape(50),color=if(active)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(text,Modifier.padding(horizontal=12.dp,vertical=7.dp),style=MaterialTheme.typography.labelMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}

@Composable
fun WorkspaceAction(title:String,detail:String,icon:ImageVector,modifier:Modifier=Modifier,enabled:Boolean=true,onClick:()->Unit) {
    Surface(onClick=onClick,enabled=enabled,modifier=modifier,shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Icon(icon,null,Modifier.size(26.dp),tint=MaterialTheme.colorScheme.primary)
            Text(title,style=MaterialTheme.typography.titleSmall)
            if(detail.isNotBlank())Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyWorkspace(title:String,icon:ImageVector,modifier:Modifier=Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SettingLeadingIcon(icon)
        Text(title,style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
