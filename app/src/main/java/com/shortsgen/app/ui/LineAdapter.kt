package com.shortsgen.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shortsgen.app.R

/** Numbered list of the lines that will each become one video. */
class LineAdapter(
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<LineAdapter.LineViewHolder>() {

    private val items = ArrayList<String>()

    fun submit(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_line, parent, false)
        return LineViewHolder(view)
    }

    override fun onBindViewHolder(holder: LineViewHolder, position: Int) {
        holder.bind(position, items[position])
    }

    inner class LineViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val number: TextView = itemView.findViewById(R.id.line_number)
        private val text: TextView = itemView.findViewById(R.id.line_text)
        private val delete: ImageButton = itemView.findViewById(R.id.line_delete)

        fun bind(position: Int, value: String) {
            number.text = (position + 1).toString()
            text.text = value
            delete.setOnClickListener {
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onDelete(index)
            }
        }
    }
}
