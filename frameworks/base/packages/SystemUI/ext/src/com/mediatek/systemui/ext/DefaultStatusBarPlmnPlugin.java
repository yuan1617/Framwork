package com.mediatek.systemui.ext;

import android.content.Context;
import android.content.ContextWrapper;
import com.mediatek.xlog.Xlog;
import android.widget.TextView;

import com.mediatek.common.PluginImpl ;

/**
 * Default implementation of Plug-in definition of Status bar.
 */
@PluginImpl(interfaceName="com.mediatek.systemui.ext.IStatusBarPlmnPlugin")

  public class DefaultStatusBarPlmnPlugin extends ContextWrapper implements IStatusBarPlmnPlugin {
    static final String TAG = "DefaultStatusBarPlmnPlugin";
    public DefaultStatusBarPlmnPlugin(Context context) {
        super(context);
    }

   public TextView getPlmnTextView(Context cntx) {
    Xlog.d(TAG, "into DefaultStatusBarPlmnPlugin getPlmnTextView");
         return null;
     }

  public void bindSettingService(Context cntx) {
    Xlog.d(TAG, "into DefaultStatusBarPlmnPlugin bindSettingService");
    }
}
