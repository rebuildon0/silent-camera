package com.rebuildon0.silentcamera

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.AttributeSet
import android.util.LruCache
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Grid of captured items. Long-press to select items and delete them. */
class GalleryActivity : AppCompatActivity() {

    private companion object {
        const val MENU_SELECT_ALL = 1
        const val MENU_DELETE = 2
    }

    private lateinit var grid: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: GridAdapter
    private var actionMode: ActionMode? = null

    private val deleter = MediaDeleter(this) {
        actionMode?.finish()
        reload()
    }

    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(0, MENU_SELECT_ALL, 0, R.string.select_all)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, MENU_DELETE, 1, R.string.delete)
                .setIcon(R.drawable.ic_delete)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                MENU_SELECT_ALL -> {
                    adapter.selectAll()
                    updateSelectionTitle()
                }
                MENU_DELETE -> deleter.confirmAndDelete(adapter.selectedItems())
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            adapter.clearSelection()
            actionMode = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        grid = findViewById(R.id.grid)
        emptyText = findViewById(R.id.emptyText)

        val spanCount = (resources.configuration.screenWidthDp / 120).coerceAtLeast(3)
        val thumbPx = resources.displayMetrics.widthPixels / spanCount
        adapter = GridAdapter(
            scope = lifecycleScope,
            thumbPx = thumbPx,
            onClick = { position ->
                if (actionMode != null) toggle(position)
                else startActivity(
                    Intent(this, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_POSITION, position)
                )
            },
            onLongClick = { position ->
                if (actionMode == null) actionMode = startSupportActionMode(selectionCallback)
                toggle(position)
            },
        )
        grid.layoutManager = GridLayoutManager(this, spanCount)
        grid.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun reload() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { MediaRepo.loadAll(this@GalleryActivity) }
            adapter.submit(items)
            emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            if (items.isEmpty()) actionMode?.finish() else if (actionMode != null) updateSelectionTitle()
        }
    }

    private fun toggle(position: Int) {
        adapter.toggle(position)
        if (adapter.selectedCount() == 0) actionMode?.finish() else updateSelectionTitle()
    }

    private fun updateSelectionTitle() {
        actionMode?.title = getString(R.string.selected_count, adapter.selectedCount())
    }
}

private class GridAdapter(
    private val scope: CoroutineScope,
    private val thumbPx: Int,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
) : RecyclerView.Adapter<GridAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val playIcon: ImageView = view.findViewById(R.id.playIcon)
        val duration: TextView = view.findViewById(R.id.duration)
        val check: ImageView = view.findViewById(R.id.check)
        var job: Job? = null
    }

    private var items: List<MediaItem> = emptyList()
    private val selected = mutableSetOf<android.net.Uri>()
    private val cache = LruCache<Long, Bitmap>(120)

    fun submit(newItems: List<MediaItem>) {
        items = newItems
        selected.retainAll(newItems.map { it.uri }.toSet())
        notifyDataSetChanged()
    }

    fun toggle(position: Int) {
        val uri = items.getOrNull(position)?.uri ?: return
        if (!selected.remove(uri)) selected.add(uri)
        notifyItemChanged(position)
    }

    fun selectAll() {
        selected.addAll(items.map { it.uri })
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
    }

    fun selectedCount() = selected.size

    fun selectedItems() = items.filter { it.uri in selected }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_grid, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.job?.cancel()
        holder.playIcon.visibility = if (item.isVideo) View.VISIBLE else View.GONE
        holder.duration.text = if (item.isVideo) MediaRepo.formatDuration(item.durationMs) else ""
        val isSelected = item.uri in selected
        holder.check.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.image.alpha = if (isSelected) 0.5f else 1f
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener {
            onLongClick(holder.bindingAdapterPosition)
            true
        }

        val key = item.id * 2 + if (item.isVideo) 1 else 0
        val cached = cache.get(key)
        if (cached != null) {
            holder.image.setImageBitmap(cached)
            return
        }
        holder.image.setImageDrawable(null)
        val context = holder.itemView.context
        holder.job = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { MediaRepo.thumbnail(context, item.uri, thumbPx) }
            if (bitmap != null) {
                cache.put(key, bitmap)
                holder.image.setImageBitmap(bitmap)
            }
        }
    }
}

/** A FrameLayout that is always as tall as it is wide. */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
