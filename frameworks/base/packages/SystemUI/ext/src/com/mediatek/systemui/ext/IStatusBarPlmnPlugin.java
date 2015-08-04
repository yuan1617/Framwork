package com.mediatek.systemui.ext;

import android.content.Context;
import android.widget.TextView;

/**
 * M: the interface for Plug-in definition of Status bar.
 */
public interface IStatusBarPlmnPlugin {

   TextView getPlmnTextView(Context cntx);

   void bindSettingService(Context contxt);

}
