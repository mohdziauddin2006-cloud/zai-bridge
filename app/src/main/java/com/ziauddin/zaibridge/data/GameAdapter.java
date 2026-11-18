package com.ziauddin.zaibridge.data;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.ziauddin.zaibridge.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Renders the imported library. Deliberately knows nothing about OBB files. */
public class GameAdapter extends BaseAdapter {

    private final Context context;
    private final LayoutInflater inflater;
    private final List<Game> items = new ArrayList<>();

    public GameAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    public void setGames(List<Game> games) {
        items.clear();
        if (games != null) items.addAll(games);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Game getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).packageName.hashCode();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.item_game, parent, false);
            Holder holder = new Holder();
            holder.icon = view.findViewById(R.id.iv_icon);
            holder.name = view.findViewById(R.id.tv_name);
            holder.meta = view.findViewById(R.id.tv_meta);
            view.setTag(holder);
        }

        Game game = getItem(position);
        Holder holder = (Holder) view.getTag();
        holder.name.setText(game.label);

        File dir = GameRepository.gameDir(context, game.directoryName);
        StringBuilder meta = new StringBuilder("32-bit");
        if (game.hasObb()) meta.append(" · OBB");
        // Only a per-game override is shown here; the global default stays
        // implicit so the cards do not all repeat the same label.
        GraphicsSettings graphics = GraphicsSettings.loadGame(dir);
        if (graphics != null && graphics.active()) {
            meta.append(" · ").append(graphics.shortLabel());
        }
        holder.meta.setText(meta);

        File icon = game.iconFile.isEmpty() ? null : new File(dir, game.iconFile);
        if (icon != null && icon.isFile()) {
            holder.icon.setImageBitmap(BitmapFactory.decodeFile(icon.getAbsolutePath()));
        } else {
            holder.icon.setImageResource(R.mipmap.ic_launcher);
        }
        return view;
    }

    private static final class Holder {
        ImageView icon;
        TextView name;
        TextView meta;
    }
}
