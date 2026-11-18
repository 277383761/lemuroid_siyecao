package com.swordfish.lemuroid.app.tv.home

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.lib.library.MetaSystemID

class SystemPresenter(
    private val cardSize: Int,
    private val cardPadding: Int,
    private val onSystemLongClick: (MetaSystemID) -> Unit = {}
) : Presenter() {

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder?, item: Any) {
        val systemInfo = item as MetaSystemInfo
        val context = (viewHolder as ViewHolder).view.context

        viewHolder.mCardView.titleText = context.resources.getString(systemInfo.metaSystem.titleResId)
        val details = context.getString(R.string.system_grid_details, systemInfo.count.toString())
        viewHolder.mCardView.contentText = if (systemInfo.isDisabled) {
            "${context.getString(R.string.system_disabled_label)} · $details"
        } else {
            details
        }
        viewHolder.mCardView.setMainImageDimensions(cardSize, cardSize)
        viewHolder.mCardView.mainImageView.setImageResource(systemInfo.metaSystem.imageResId)
        viewHolder.mCardView.mainImageView.setPadding(cardPadding, cardPadding, cardPadding, cardPadding)
        viewHolder.mCardView.setMainImageScaleType(ImageView.ScaleType.FIT_CENTER)
        viewHolder.mCardView.alpha = if (systemInfo.isDisabled) 0.4f else 1f

        // Long press (D-pad centre held) opens the option to hide this system.
        viewHolder.mCardView.setOnLongClickListener {
            onSystemLongClick(systemInfo.metaSystem)
            true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val cardView = ImageCardView(parent.context)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        (cardView.findViewById<View>(R.id.content_text) as TextView).setTextColor(Color.LTGRAY)
        return ViewHolder(cardView)
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder?) {}

    class ViewHolder(view: ImageCardView) : Presenter.ViewHolder(view) {
        val mCardView: ImageCardView = view
    }
}
