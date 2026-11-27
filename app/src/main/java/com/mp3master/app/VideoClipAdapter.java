package com.mp3master.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoClipAdapter extends RecyclerView.Adapter<VideoClipAdapter.ViewHolder> {

    public interface OnClipSelectedListener {
        void onClipSelected(int position);
    }

    private final List<AudioUtils.VideoInfo> clips;
    private final OnClipSelectedListener listener;
    private int selectedIndex = 0;

    private final Map<Uri, Bitmap> thumbCache = new HashMap<>();
    private final ExecutorService thumbExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public VideoClipAdapter(List<AudioUtils.VideoInfo> clips, OnClipSelectedListener listener) {
        this.clips = clips;
        this.listener = listener;
    }

    public void setSelectedIndex(int index) {
        this.selectedIndex = index;
        notifyDataSetChanged();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video_clip, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AudioUtils.VideoInfo clip = clips.get(position);
        boolean isSelected = (position == selectedIndex);
        holder.root.setBackgroundResource(isSelected ? R.drawable.bg_clip_selected : R.drawable.bg_clip_normal);
        holder.tvIndex.setText(String.valueOf(position + 1));
        holder.tvDur.setText(AudioUtils.formatTimeMs(clip.getEffectiveDurationMs()));

        Bitmap cached = thumbCache.get(clip.uri);
        if (cached != null) {
            holder.ivThumb.setImageBitmap(cached);
        } else {
            holder.ivThumb.setImageDrawable(null);
            loadThumbAsync(holder.itemView.getContext().getApplicationContext(), clip.uri, position);
        }

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos >= 0 && pos < clips.size()) {
                selectedIndex = pos;
                notifyDataSetChanged();
                listener.onClipSelected(pos);
            }
        });
    }

    private void loadThumbAsync(Context appCtx, Uri uri, int position) {
        thumbExecutor.execute(() -> {
            Bitmap bmp = null;
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(appCtx, uri);
                Bitmap raw = mmr.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (raw != null) {
                    bmp = Bitmap.createScaledBitmap(raw, 224, 136, true);
                    if (bmp != raw) raw.recycle();
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    mmr.release();
                } catch (Exception ignored) {
                }
            }
            if (bmp != null) {
                final Bitmap finalBmp = bmp;
                mainHandler.post(() -> {
                    thumbCache.put(uri, finalBmp);
                    if (position >= 0 && position < clips.size()) {
                        notifyItemChanged(position);
                    }
                });
            }
        });
    }

    @Override
    public int getItemCount() {
        return clips.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View root;
        ImageView ivThumb;
        TextView tvIndex, tvDur;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            root = itemView.findViewById(R.id.clipCardRoot);
            ivThumb = itemView.findViewById(R.id.ivClipThumb);
            tvIndex = itemView.findViewById(R.id.tvClipIndex);
            tvDur = itemView.findViewById(R.id.tvClipDur);
        }
    }
}
