package com.rebuildon0.silentcamera

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Full-screen preview of captured photos and videos; swipe to move between them. */
class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_POSITION = "position"
    }

    private lateinit var pager: ViewPager2
    private lateinit var counter: TextView
    private var adapter: PageAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        pager = findViewById(R.id.pager)
        counter = findViewById(R.id.counter)
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        val start = intent.getIntExtra(EXTRA_POSITION, 0)
        val maxDim = resources.displayMetrics.let { max(it.widthPixels, it.heightPixels) }
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { MediaRepo.loadAll(this@ViewerActivity) }
            if (items.isEmpty()) {
                finish()
                return@launch
            }
            val pageAdapter = PageAdapter(items, lifecycleScope, maxDim)
            adapter = pageAdapter
            pager.adapter = pageAdapter
            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    pageAdapter.stopPlayback()
                    counter.text = "${position + 1} / ${items.size}"
                }
            })
            pager.setCurrentItem(start.coerceIn(0, items.size - 1), false)
        }
    }

    override fun onPause() {
        adapter?.stopPlayback()
        super.onPause()
    }
}

private class PageAdapter(
    private val items: List<MediaItem>,
    private val scope: CoroutineScope,
    private val maxDim: Int,
) : RecyclerView.Adapter<PageAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val video: VideoView = view.findViewById(R.id.video)
        val play: ImageButton = view.findViewById(R.id.play)
        var job: Job? = null
    }

    private var playing: Holder? = null

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.page_media, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        if (playing === holder) stopPlayback()
        resetToStill(holder, item.isVideo)

        holder.job?.cancel()
        holder.image.setImageDrawable(null)
        val context = holder.itemView.context
        holder.job = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                if (item.isVideo) MediaRepo.thumbnail(context, item.uri, 1024)
                else MediaRepo.decodePhoto(context, item.uri, maxDim)
            }
            holder.image.setImageBitmap(bitmap)
        }

        holder.play.setOnClickListener {
            stopPlayback()
            holder.image.visibility = View.GONE
            holder.play.visibility = View.GONE
            holder.video.visibility = View.VISIBLE
            val mediaController = MediaController(context)
            mediaController.setAnchorView(holder.video)
            holder.video.setMediaController(mediaController)
            holder.video.setVideoURI(item.uri)
            holder.video.setOnPreparedListener { holder.video.start() }
            playing = holder
        }
    }

    override fun onViewRecycled(holder: Holder) {
        if (playing === holder) stopPlayback()
        holder.job?.cancel()
    }

    fun stopPlayback() {
        val holder = playing ?: return
        playing = null
        holder.video.stopPlayback()
        holder.video.setMediaController(null)
        resetToStill(holder, true)
    }

    private fun resetToStill(holder: Holder, isVideo: Boolean) {
        holder.video.visibility = View.GONE
        holder.image.visibility = View.VISIBLE
        holder.play.visibility = if (isVideo) View.VISIBLE else View.GONE
    }
}
