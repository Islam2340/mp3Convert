package com.mp3master.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.List;

public class MergeAdapter extends RecyclerView.Adapter<MergeAdapter.ViewHolder> {

    public interface OnItemActionListener {
        void onTrim(int position);
        void onPreviewTransition(int position);
        void onMoveUp(int position);
        void onMoveDown(int position);
        void onRemove(int position);
    }

    private final List<AudioUtils.AudioInfo> items;
    private final OnItemActionListener listener;
    private int loadingTransitionIndex = -1;
    private int playingTransitionIndex = -1;

    public MergeAdapter(List<AudioUtils.AudioInfo> items, OnItemActionListener listener) {
        this.items = items;
        this.listener = listener;
    }

    public void setTransitionState(int loadingIndex, int playingIndex) {
        this.loadingTransitionIndex = loadingIndex;
        this.playingTransitionIndex = playingIndex;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_merge_file, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AudioUtils.AudioInfo info = items.get(position);
        holder.tvOrder.setText(String.valueOf(position + 1));
        holder.tvName.setText(info.displayName);

        if (info.isTrimmed()) {
            holder.tvMeta.setText(
                    "Обрезан: " + AudioUtils.formatTimePreciseMs(info.trimStartMs) +
                    " – " + AudioUtils.formatTimePreciseMs(info.trimEndMs) +
                    " (" + AudioUtils.formatTimeMs(info.getEffectiveDurationMs()) + ")"
            );
            holder.tvMeta.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.primary));
        } else {
            holder.tvMeta.setText(
                    AudioUtils.formatTimeMs(info.durationMs) + "  •  " + AudioUtils.formatSize(info.sizeBytes)
            );
            holder.tvMeta.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.text_secondary));
        }

        holder.btnUp.setEnabled(position > 0);
        holder.btnUp.setAlpha(position > 0 ? 1f : 0.3f);
        holder.btnDown.setEnabled(position < items.size() - 1);
        holder.btnDown.setAlpha(position < items.size() - 1 ? 1f : 0.3f);

        holder.btnTrim.setOnClickListener(v -> listener.onTrim(holder.getBindingAdapterPosition()));
        holder.btnUp.setOnClickListener(v -> listener.onMoveUp(holder.getBindingAdapterPosition()));
        holder.btnDown.setOnClickListener(v -> listener.onMoveDown(holder.getBindingAdapterPosition()));
        holder.btnRemove.setOnClickListener(v -> listener.onRemove(holder.getBindingAdapterPosition()));

        // Transition preview button between item `position` and `position + 1`
        if (position < items.size() - 1) {
            holder.btnPreviewTransition.setVisibility(View.VISIBLE);
            String pairLabel = "(" + (position + 1) + " → " + (position + 2) + ")";
            if (position == loadingTransitionIndex) {
                holder.btnPreviewTransition.setEnabled(false);
                holder.btnPreviewTransition.setText("Подготовка перехода " + pairLabel + "...");
                holder.btnPreviewTransition.setIconResource(R.drawable.ic_play);
            } else if (position == playingTransitionIndex) {
                holder.btnPreviewTransition.setEnabled(true);
                holder.btnPreviewTransition.setText("Стоп переход " + pairLabel);
                holder.btnPreviewTransition.setIconResource(R.drawable.ic_pause);
            } else {
                holder.btnPreviewTransition.setEnabled(true);
                holder.btnPreviewTransition.setText("Послушать переход " + pairLabel);
                holder.btnPreviewTransition.setIconResource(R.drawable.ic_play);
            }
            holder.btnPreviewTransition.setOnClickListener(v -> listener.onPreviewTransition(holder.getBindingAdapterPosition()));
        } else {
            holder.btnPreviewTransition.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvOrder, tvName, tvMeta;
        ImageButton btnTrim, btnUp, btnDown, btnRemove;
        MaterialButton btnPreviewTransition;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvOrder = itemView.findViewById(R.id.tvItemOrder);
            tvName = itemView.findViewById(R.id.tvItemName);
            tvMeta = itemView.findViewById(R.id.tvItemMeta);
            btnTrim = itemView.findViewById(R.id.btnTrimItem);
            btnUp = itemView.findViewById(R.id.btnMoveUp);
            btnDown = itemView.findViewById(R.id.btnMoveDown);
            btnRemove = itemView.findViewById(R.id.btnRemoveItem);
            btnPreviewTransition = itemView.findViewById(R.id.btnPreviewTransition);
        }
    }
}
