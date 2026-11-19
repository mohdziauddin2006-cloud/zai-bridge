package com.ziauddin.zaibridge.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.ziauddin.zaibridge.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders the imported library as an animated glass grid.
 *
 * <p>Each card samples its own icon for a dominant colour and paints that as a
 * radial ambient glow behind the art, so the grid reads as a wall of lit
 * objects rather than a list of thumbnails. Touch presses spring the card down
 * and overshoot back, which is the only motion in the library: nothing moves on
 * its own.
 */
public class GameAdapter extends RecyclerView.Adapter<GameAdapter.Holder> {

    public interface Listener {
        void onPlay(Game game);

        void onDetails(Game game);
    }

    private final Context context;
    private final Listener listener;
    private final List<Game> all = new ArrayList<>();
    private final List<Game> visible = new ArrayList<>();
    private String query = "";

    public GameAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setGames(List<Game> games) {
        all.clear();
        if (games != null) all.addAll(games);
        applyFilter();
    }

    public void setQuery(String text) {
        query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    public boolean isEmpty() {
        return visible.isEmpty();
    }

    private void applyFilter() {
        visible.clear();
        for (Game game : all) {
            if (query.isEmpty()
                    || game.label.toLowerCase(Locale.ROOT).contains(query)
                    || game.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                visible.add(game);
            }
        }
        notifyDataSetChanged();
    }

    public Game getItem(int position) {
        return visible.get(position);
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).packageName.hashCode();
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_game, parent, false);
        Holder holder = new Holder(view);
        view.setOnClickListener(v -> {
            int position = holder.getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION) listener.onPlay(getItem(position));
        });
        view.setOnLongClickListener(v -> {
            int position = holder.getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION) listener.onDetails(getItem(position));
            return true;
        });
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.955f).scaleY(0.955f)
                            .setDuration(110)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(280)
                            .setInterpolator(new OvershootInterpolator(2.6f))
                            .start();
                    break;
                default:
                    break;
            }
            return false;
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Game game = getItem(position);
        holder.name.setText(game.label);

        File dir = GameRepository.gameDir(context, game.directoryName);
        GraphicsSettings graphics = GraphicsSettings.forGame(context, dir);

        long size = game.sizeBytes;
        String splitSuffix = game.splitCount > 0 ? " · SPLIT" : "";
        holder.meta.setText(shortSize(size) + " · " + game.abi + splitSuffix);

        if (holder.abiBadge != null) {
            if (game.isNative64()) {
                holder.abiBadge.setText("64-BIT");
                holder.abiBadge.setBackgroundResource(R.drawable.bg_badge_accent);
                holder.abiBadge.setTextColor(context.getColor(R.color.zb_accent));
            } else {
                holder.abiBadge.setText("32-BIT");
                holder.abiBadge.setBackgroundResource(R.drawable.bg_badge_amber);
                holder.abiBadge.setTextColor(context.getColor(R.color.zb_amber));
            }
        }

        String engine = graphics.badge();
        holder.engine.setVisibility(engine.isEmpty() ? View.GONE : View.VISIBLE);
        if (!engine.isEmpty()) {
            holder.engine.setText(graphics.frameGen == GraphicsSettings.FRAME_GEN_OFF
                    ? engine : engine + " " + graphics.frameGenName());
        }
        holder.obb.setVisibility(game.hasObb() ? View.VISIBLE : View.GONE);

        Bitmap bitmap = null;
        File icon = game.iconFile.isEmpty() ? null : new File(dir, game.iconFile);
        if (icon != null && icon.isFile()) {
            bitmap = BitmapFactory.decodeFile(icon.getAbsolutePath());
        }
        if (bitmap != null) {
            RoundedBitmapDrawable rounded = RoundedBitmapDrawableFactory.create(
                    context.getResources(), bitmap);
            rounded.setCornerRadius(dp(20));
            rounded.setAntiAlias(true);
            holder.icon.setImageDrawable(rounded);
            holder.glow.setBackground(glow(dominantColor(bitmap)));
        } else {
            holder.icon.setImageResource(R.mipmap.ic_launcher);
            holder.glow.setBackgroundResource(R.drawable.bg_ambient_glow);
        }
        holder.card.setAlpha(0.92f);
        holder.card.setScaleX(1f);
        holder.card.setScaleY(1f);
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        super.onViewRecycled(holder);
        holder.icon.setImageDrawable(null);
    }

    // ------------------------------------------------------------------
    // Art helpers
    // ------------------------------------------------------------------

    private static int dominantColor(Bitmap bitmap) {
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) return 0xFF00E5FF;

        long r = 0, g = 0, b = 0;
        int samples = 0;
        int stepX = Math.max(1, w / 18);
        int stepY = Math.max(1, h / 18);
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) < 48) continue;
                int rr = Color.red(pixel);
                int gg = Color.green(pixel);
                int bb = Color.blue(pixel);
                int max = Math.max(rr, Math.max(gg, bb));
                int min = Math.min(rr, Math.min(gg, bb));
                if (max - min < 26) continue;
                r += rr;
                g += gg;
                b += bb;
                ++samples;
            }
        }
        if (samples == 0) return 0xFF00E5FF;

        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / samples), (int) (g / samples), (int) (b / samples), hsv);
        hsv[1] = Math.min(1f, hsv[1] * 0.9f + 0.15f);
        hsv[2] = Math.min(1f, hsv[2] * 0.7f + 0.35f);
        return Color.HSVToColor(hsv);
    }

    private Drawable glow(int color) {
        int core = (color & 0x00FFFFFF) | 0x66000000;
        int mid = (color & 0x00FFFFFF) | 0x1F000000;
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        drawable.setGradientCenter(0.5f, 0.46f);
        drawable.setGradientRadius(dp(74));
        drawable.setColors(new int[]{core, mid, 0x00000000});
        drawable.setCornerRadius(dp(18));
        return drawable;
    }

    private static String shortSize(long bytes) {
        if (bytes <= 0) return "—";
        if (bytes < 1024L * 1024L) return (bytes / 1024L) + " KB";
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final View glow;
        final ImageView icon;
        final TextView name;
        final TextView meta;
        final TextView engine;
        final TextView abiBadge;
        final TextView obb;

        Holder(View view) {
            super(view);
            card = (MaterialCardView) view;
            glow = view.findViewById(R.id.view_glow);
            icon = view.findViewById(R.id.iv_icon);
            name = view.findViewById(R.id.tv_name);
            meta = view.findViewById(R.id.tv_meta);
            engine = view.findViewById(R.id.tv_badge_engine);
            abiBadge = view.findViewById(R.id.tv_badge_abi);
            obb = view.findViewById(R.id.tv_badge_obb);
        }
    }
}
