package com.mp3master.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class VideoMergeAdapter extends RecyclerView.Adapter<VideoMergeAdapter.ViewHolder> {

    public interface OnVideoActionListener {
        void onMoveUp(int position);
        void onMoveDown(int position);
        void onRemove(int position);
    }

    private final List<AudioUtils.VideoInfo> items;
    private final OnVideoActionListener listener;

    public VideoMergeAdapter(List<AudioUtils.VideoInfo> items, OnVideoActionListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video_merge, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AudioUtils.VideoInfo info = items.get(position);
        holder.tvOrder.setText(String.valueOf(position + 1));
        holder.tvName.setText(info.displayName);
        String res = (info.getDisplayWidth() > 0 && info.getDisplayHeight() > 0)
                ? (info.getDisplayWidth() + "×" + info.getDisplayHeight() + "  •  ") : "";
        holder.tvMeta.setText(
                res + AudioUtils.formatTimeMs(info.durationMs) + "  •  " + AudioUtils.formatSize(info.sizeBytes)
        );

        holder.btnUp.setEnabled(position > 0);
        holder.btnUp.setAlpha(position > 0 ? 1f : 0.3f);
        holder.btnDown.setEnabled(position < items.size() - 1);
        holder.btnDown.setAlpha(position < items.size() - 1 ? 1f : 0.3f);

        holder.btnUp.setOnClickListener(v -> listener.onMoveUp(holder.getBindingAdapterPosition()));
        holder.btnDown.setOnClickListener(v -> listener.onMoveDown(holder.getBindingAdapterPosition()));
        holder.btnRemove.setOnClickListener(v -> listener.onRemove(holder.getBindingAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvOrder, tvName, tvMeta;
        ImageButton btnUp, btnDown, btnRemove;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvOrder = itemView.findViewById(R.id.tvVideoOrder);
            tvName = itemView.findViewById(R.id.tvVideoName);
            tvMeta = itemView.findViewById(R.id.tvVideoMeta);
            btnUp = itemView.findViewById(R.id.btnVideoUp);
            btnDown = itemView.findViewById(R.id.btnVideoDown);
            btnRemove = itemView.findViewById(R.id.btnVideoRemove);
        }
    }
}
