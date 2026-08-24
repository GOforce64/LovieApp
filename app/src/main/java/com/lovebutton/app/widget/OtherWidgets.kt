package com.lovebutton.app.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class ThinkingWidget : MessageWidget(msgId = 2)

class ThinkingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ThinkingWidget()
}

class MissWidget : MessageWidget(msgId = 3)

class MissWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MissWidget()
}

class CallWidget : MessageWidget(msgId = 4)

class CallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CallWidget()
}
