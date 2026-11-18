package com.swordfish.lemuroid.app.tv.games

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.paging.PagingDataAdapter
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.VerticalGridView
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.ui.PlayerView
import androidx.navigation.fragment.navArgs
import androidx.paging.LoadState
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.GameInteractor
import com.swordfish.lemuroid.app.shared.covers.CoverLoader
import com.swordfish.lemuroid.app.shared.covers.VideoPreviewController
import com.swordfish.lemuroid.app.tv.shared.GamePresenter
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import dagger.android.support.AndroidSupportInjection
import javax.inject.Inject

class TVGamesFragment : VerticalGridSupportFragment() {

    @Inject
    lateinit var retrogradeDb: RetrogradeDatabase

    @Inject
    lateinit var gameInteractor: GameInteractor

    @Inject
    lateinit var coverLoader: CoverLoader

    private val args: TVGamesFragmentArgs by navArgs()

    private var detailTitle: TextView? = null
    private var detailCover: ImageView? = null
    private var detailVideo: PlayerView? = null
    private var detailMeta: TextView? = null

    init {
        val gridPresenter = VerticalGridPresenter()
        gridPresenter.numberOfColumns = 4
        setGridPresenter(gridPresenter)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val factory = TVGamesViewModel.Factory(retrogradeDb)
        val gamesViewModel = ViewModelProvider(this, factory)[TVGamesViewModel::class.java]

        val cardSize = resources.getDimensionPixelSize(R.dimen.card_size)
        val pagingAdapter = PagingDataAdapter(
            GamePresenter(cardSize, gameInteractor, coverLoader),
            Game.DIFF_CALLBACK
        )

        this.adapter = pagingAdapter

        launchOnState(Lifecycle.State.RESUMED) {
            gamesViewModel.games
                .collect { pagingAdapter.submitData(lifecycle, it) }
        }

        args.systemIds.let {
            gamesViewModel.systemIds.value = listOf(*it)
        }

        pagingAdapter.addLoadStateListener { states ->
            if (states.refresh is LoadState.NotLoading) requestGridFocus()
        }
        requestGridFocus()

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is Game -> gameInteractor.onGamePlay(item)
            }
        }

        setOnItemViewSelectedListener(OnItemViewSelectedListener { itemViewHolder, item, _, _ ->
            if (item !is Game) return@OnItemViewSelectedListener
            val cardView = (itemViewHolder?.view as? ImageCardView)
                ?: (view?.findVerticalGridView()?.findFocus() as? ImageCardView)
                ?: return@OnItemViewSelectedListener
            updateDetailPanel(item)
            VideoPreviewController.onCardFocused(cardView, item)
        })

        // 天马G风格：左侧详情面板 + 右侧游戏网格。面板添加到 BrowseFrameLayout，
        // 网格向右偏移避免被遮挡。
        setupDetailPanel(view)
    }

    private fun setupDetailPanel(root: View) {
        val host = root as? ViewGroup ?: return

        // Inflate the detail panel and pin it to the left edge of the screen.
        val panel = LayoutInflater.from(requireContext())
            .inflate(R.layout.view_tv_game_detail_panel, host, false)
        val panelWidth = resources.getDimensionPixelSize(R.dimen.tv_detail_panel_width)
        panel.layoutParams = FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START
        }
        host.addView(panel)

        detailTitle = panel.findViewById(R.id.detail_title)
        detailCover = panel.findViewById(R.id.detail_cover)
        detailVideo = panel.findViewById(R.id.detail_video)
        detailMeta = panel.findViewById(R.id.detail_meta)

        // Bind the panel's PlayerView as the preview render target.
        detailVideo?.let { VideoPreviewController.attachPlayerView(it) }

        // Shift the grid to the right so the cards are not hidden behind the panel.
        root.post {
            val grid = root.findVerticalGridView() ?: return@post
            grid.setPadding(panelWidth, grid.paddingTop, grid.paddingRight, grid.paddingBottom)
            grid.clipToPadding = false
        }
    }

    private fun updateDetailPanel(game: Game) {
        detailTitle?.text = game.title

        val systemName = runCatching {
            val system = com.swordfish.lemuroid.lib.library.GameSystem.findById(game.systemId)
            getString(system.titleResId)
        }.getOrNull() ?: game.systemId
        val developer = game.developer?.takeIf { it.isNotBlank() }
        val meta = listOfNotNull(systemName, developer).joinToString("  ·  ")
        detailMeta?.text = meta

        // Load the cover into the panel; the video preview (if any) fades in over it.
        detailCover?.let { coverLoader.loadCover(game, it) }
    }

    override fun onPause() {
        VideoPreviewController.stopPreview()
        super.onPause()
    }

    override fun onDestroyView() {
        VideoPreviewController.detachPlayerView()
        detailTitle = null
        detailCover = null
        detailVideo = null
        detailMeta = null
        super.onDestroyView()
    }

    private fun requestGridFocus() {
        val root = view ?: return
        val gridView = root.findVerticalGridView() ?: return
        root.post {
            if (!gridView.hasFocus()) gridView.requestFocus()
        }
    }

    private fun View.findVerticalGridView(): VerticalGridView? {
        if (this is VerticalGridView) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).findVerticalGridView()?.let { return it }
            }
        }
        return null
    }

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    @dagger.Module
    class Module
}
