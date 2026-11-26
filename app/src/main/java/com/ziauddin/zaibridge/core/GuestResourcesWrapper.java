package com.ziauddin.zaibridge.core;

import android.annotation.SuppressLint;
import android.content.res.AssetFileDescriptor;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Movie;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Crash-proof {@link Resources} wrapper for virtualized guests.
 *
 * <p>Solves Android 8–16 split-APK and multi-package resource lookups:
 * <ol>
 *   <li>{@link #getIdentifier(String, String, String)} searches (1) guest {@code Resources}
 *       with {@code guestPackage}, (2) guest {@code Resources} with caller {@code defPackage},
 *       and (3) host {@code com.ziauddin.zaibridge} {@code Resources}, mapping host-only IDs
 *       into a collision-free {@code 0x6a......} ID table so host fallback IDs never shadow
 *       a guest's {@code 0x7f......} resources.</li>
 *   <li>{@link #getString(int)}, {@link #getString(int, Object...)}, {@link #getText(int)},
 *       and {@link #getText(int, CharSequence)} NEVER throw
 *       {@code Resources$NotFoundException: String resource ID #0x0}: ID {@code 0} logs once
 *       and returns {@code ""} (or {@code def}), and any missing guest resource falls back
 *       cleanly to the host Zai-Bridge {@code Resources}.</li>
 * </ol>
 */
@SuppressWarnings("deprecation")
public final class GuestResourcesWrapper extends Resources {

    private static final String TAG = "ZaiBridgeRes";
    private static final int SYNTHETIC_HOST_PREFIX = 0x6a000000;
    private static final int SYNTHETIC_MASK = 0x00ffffff;
    private static final AtomicBoolean LOGGED_ZERO_LOOKUP = new AtomicBoolean(false);

    private final Resources guestPrimary;
    private final Resources hostResources;
    private final String guestPackage;
    private final String hostPackage;

    private final AtomicInteger nextSynthetic = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, Integer> syntheticToHost = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Integer> hostToSynthetic = new ConcurrentHashMap<>();

    public GuestResourcesWrapper(
            @NonNull Resources guestPrimary,
            @NonNull Resources hostResources,
            @NonNull String guestPackage,
            @NonNull String hostPackage) {
        super(guestPrimary.getAssets(), guestPrimary.getDisplayMetrics(), guestPrimary.getConfiguration());
        this.guestPrimary = guestPrimary;
        this.hostResources = hostResources;
        this.guestPackage = guestPackage;
        this.hostPackage = hostPackage;
    }

    private static final String SAFE_ZERO_STRING = "Game view";

    @NonNull
    public Resources getGuestPrimary() {
        return guestPrimary;
    }

    @NonNull
    public GuestResourcesWrapper withConfiguration(
            @Nullable Configuration config, @Nullable DisplayMetrics metrics) {
        Resources updatedGuest = new Resources(
                guestPrimary.getAssets(),
                metrics != null ? metrics : guestPrimary.getDisplayMetrics(),
                config != null ? config : guestPrimary.getConfiguration());
        return new GuestResourcesWrapper(updatedGuest, hostResources, guestPackage, hostPackage);
    }

    private int toSyntheticHostId(int hostId) {
        if (hostId == 0) return 0;
        Integer existing = hostToSynthetic.get(hostId);
        if (existing != null) return existing;

        int syn;
        do {
            int seq = nextSynthetic.getAndIncrement() & SYNTHETIC_MASK;
            if (seq == 0) seq = nextSynthetic.getAndIncrement() & SYNTHETIC_MASK;
            syn = SYNTHETIC_HOST_PREFIX | seq;
        } while (syntheticToHost.containsKey(syn));

        Integer prev = hostToSynthetic.putIfAbsent(hostId, syn);
        if (prev != null) return prev;
        syntheticToHost.put(syn, hostId);
        return syn;
    }

    @Nullable
    private Integer resolveSyntheticHostId(int id) {
        return syntheticToHost.get(id);
    }

    private static void logZeroIdOnce(String caller) {
        if (LOGGED_ZERO_LOOKUP.compareAndSet(false, true)) {
            Log.w(TAG, caller + "(0x0) intercepted — returning safe empty string instead of throwing NotFoundException");
        }
    }

    @Override
    public int getIdentifier(String name, String defType, String defPackage) {
        if (name == null || name.isEmpty()) return 0;

        // 1. Primary search against the guest's own package & AssetManager (base + splits).
        if (!guestPackage.isEmpty()) {
            try {
                int id = guestPrimary.getIdentifier(name, defType, guestPackage);
                if (id != 0) return id;
            } catch (Throwable ignored) {
            }
        }

        // 2. Search guest Resources with the caller's defPackage (handles "android" or library namespaces).
        if (defPackage != null && !defPackage.isEmpty() && !defPackage.equals(guestPackage)) {
            try {
                int id = guestPrimary.getIdentifier(name, defType, defPackage);
                if (id != 0) return id;
            } catch (Throwable ignored) {
            }
        } else if (defPackage == null) {
            try {
                int id = guestPrimary.getIdentifier(name, defType, null);
                if (id != 0) return id;
            } catch (Throwable ignored) {
            }
        }

        // 3. Fallback to host Zai-Bridge resources (contains Unity's mandatory accessibility/window strings).
        try {
            int hostId = hostResources.getIdentifier(name, defType, hostPackage);
            if (hostId == 0 && defPackage != null && !defPackage.equals(hostPackage)) {
                hostId = hostResources.getIdentifier(name, defType, defPackage);
            }
            if (hostId != 0) {
                return toSyntheticHostId(hostId);
            }
        } catch (Throwable ignored) {
        }

        return 0;
    }

    @NonNull
    @Override
    public String getString(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getString");
            return SAFE_ZERO_STRING;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) {
            return hostResources.getString(hostId);
        }
        try {
            return guestPrimary.getString(id);
        } catch (NotFoundException nfe) {
            return hostResources.getString(id);
        }
    }

    @NonNull
    @Override
    public String getString(int id, Object... formatArgs) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getString(formatArgs)");
            return SAFE_ZERO_STRING;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) {
            return hostResources.getString(hostId, formatArgs);
        }
        try {
            return guestPrimary.getString(id, formatArgs);
        } catch (NotFoundException nfe) {
            return hostResources.getString(id, formatArgs);
        }
    }

    @NonNull
    @Override
    public CharSequence getText(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getText");
            return SAFE_ZERO_STRING;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) {
            return hostResources.getText(hostId);
        }
        try {
            return guestPrimary.getText(id);
        } catch (NotFoundException nfe) {
            return hostResources.getText(id);
        }
    }

    @Override
    public CharSequence getText(int id, CharSequence def) {
        if (id == 0) {
            logZeroIdOnce("getText(def)");
            return def != null ? def : SAFE_ZERO_STRING;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) {
            return hostResources.getText(hostId, def);
        }
        try {
            CharSequence v = guestPrimary.getText(id, null);
            if (v != null) return v;
        } catch (Throwable ignored) {
        }
        CharSequence hostVal = hostResources.getText(id, def);
        return hostVal != null ? hostVal : (def != null ? def : SAFE_ZERO_STRING);
    }

    @NonNull
    @Override
    public CharSequence getQuantityText(int id, int quantity) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getQuantityText");
            return SAFE_ZERO_STRING;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getQuantityText(hostId, quantity);
        try {
            return guestPrimary.getQuantityText(id, quantity);
        } catch (NotFoundException nfe) {
            return hostResources.getQuantityText(id, quantity);
        }
    }

    @NonNull
    @Override
    public String getQuantityString(int id, int quantity) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getQuantityString");
            return SAFE_ZERO_STRING;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getQuantityString(hostId, quantity);
        try {
            return guestPrimary.getQuantityString(id, quantity);
        } catch (NotFoundException nfe) {
            return hostResources.getQuantityString(id, quantity);
        }
    }

    @NonNull
    @Override
    public String getQuantityString(int id, int quantity, Object... formatArgs) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getQuantityString(formatArgs)");
            return SAFE_ZERO_STRING;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getQuantityString(hostId, quantity, formatArgs);
        try {
            return guestPrimary.getQuantityString(id, quantity, formatArgs);
        } catch (NotFoundException nfe) {
            return hostResources.getQuantityString(id, quantity, formatArgs);
        }
    }

    @NonNull
    @Override
    public String[] getStringArray(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getStringArray");
            return new String[0];
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getStringArray(hostId);
        try {
            return guestPrimary.getStringArray(id);
        } catch (NotFoundException nfe) {
            return hostResources.getStringArray(id);
        }
    }

    @NonNull
    @Override
    public CharSequence[] getTextArray(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getTextArray");
            return new CharSequence[0];
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getTextArray(hostId);
        try {
            return guestPrimary.getTextArray(id);
        } catch (NotFoundException nfe) {
            return hostResources.getTextArray(id);
        }
    }

    @NonNull
    @Override
    public int[] getIntArray(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getIntArray");
            return new int[0];
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getIntArray(hostId);
        try {
            return guestPrimary.getIntArray(id);
        } catch (NotFoundException nfe) {
            return hostResources.getIntArray(id);
        }
    }

    @Override
    public boolean getBoolean(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getBoolean");
            return false;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getBoolean(hostId);
        try {
            return guestPrimary.getBoolean(id);
        } catch (NotFoundException nfe) {
            return hostResources.getBoolean(id);
        }
    }

    @Override
    public int getInteger(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getInteger");
            return 0;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getInteger(hostId);
        try {
            return guestPrimary.getInteger(id);
        } catch (NotFoundException nfe) {
            return hostResources.getInteger(id);
        }
    }

    @SuppressLint("NewApi")
    @Override
    public float getFloat(int id) {
        if (id == 0) {
            logZeroIdOnce("getFloat");
            return 0f;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) {
            if (Build.VERSION.SDK_INT >= 29) return hostResources.getFloat(hostId);
            TypedValue tv = new TypedValue();
            hostResources.getValue(hostId, tv, true);
            return tv.getFloat();
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) return guestPrimary.getFloat(id);
            TypedValue tv = new TypedValue();
            guestPrimary.getValue(id, tv, true);
            return tv.getFloat();
        } catch (NotFoundException nfe) {
            if (Build.VERSION.SDK_INT >= 29) return hostResources.getFloat(id);
            TypedValue tv = new TypedValue();
            hostResources.getValue(id, tv, true);
            return tv.getFloat();
        }
    }

    @Override
    public int getColor(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getColor");
            return 0;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getColor(hostId);
        try {
            return guestPrimary.getColor(id);
        } catch (NotFoundException nfe) {
            return hostResources.getColor(id);
        }
    }

    @Override
    public int getColor(int id, @Nullable Theme theme) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getColor(theme)");
            return 0;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getColor(hostId, null);
        try {
            return guestPrimary.getColor(id, theme);
        } catch (NotFoundException nfe) {
            return hostResources.getColor(id, null);
        }
    }

    @NonNull
    @Override
    public ColorStateList getColorStateList(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getColorStateList");
            return ColorStateList.valueOf(0);
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getColorStateList(hostId);
        try {
            return guestPrimary.getColorStateList(id);
        } catch (NotFoundException nfe) {
            return hostResources.getColorStateList(id);
        }
    }

    @NonNull
    @Override
    public ColorStateList getColorStateList(int id, @Nullable Theme theme) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getColorStateList(theme)");
            return ColorStateList.valueOf(0);
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getColorStateList(hostId, null);
        try {
            return guestPrimary.getColorStateList(id, theme);
        } catch (NotFoundException nfe) {
            return hostResources.getColorStateList(id, null);
        }
    }

    @Override
    public float getDimension(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getDimension");
            return 0f;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getDimension(hostId);
        try {
            return guestPrimary.getDimension(id);
        } catch (NotFoundException nfe) {
            return hostResources.getDimension(id);
        }
    }

    @Override
    public int getDimensionPixelOffset(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getDimensionPixelOffset");
            return 0;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getDimensionPixelOffset(hostId);
        try {
            return guestPrimary.getDimensionPixelOffset(id);
        } catch (NotFoundException nfe) {
            return hostResources.getDimensionPixelOffset(id);
        }
    }

    @Override
    public int getDimensionPixelSize(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getDimensionPixelSize");
            return 0;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getDimensionPixelSize(hostId);
        try {
            return guestPrimary.getDimensionPixelSize(id);
        } catch (NotFoundException nfe) {
            return hostResources.getDimensionPixelSize(id);
        }
    }

    @Override
    public float getFraction(int id, int base, int pbase) {
        if (id == 0) {
            logZeroIdOnce("getFraction");
            return 0f;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getFraction(hostId, base, pbase);
        try {
            return guestPrimary.getFraction(id, base, pbase);
        } catch (NotFoundException nfe) {
            return hostResources.getFraction(id, base, pbase);
        }
    }

    @Override
    public Drawable getDrawable(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getDrawable");
            return null;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getDrawable(hostId);
        try {
            return guestPrimary.getDrawable(id);
        } catch (NotFoundException nfe) {
            return hostResources.getDrawable(id);
        }
    }

    @Override
    public Drawable getDrawable(int id, @Nullable Theme theme) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getDrawable(theme)");
            return null;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getDrawable(hostId, null);
        try {
            return guestPrimary.getDrawable(id, theme);
        } catch (NotFoundException nfe) {
            return hostResources.getDrawable(id, null);
        }
    }

    @Override
    public Drawable getDrawableForDensity(int id, int density) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getDrawableForDensity");
            return null;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getDrawableForDensity(hostId, density);
        try {
            return guestPrimary.getDrawableForDensity(id, density);
        } catch (NotFoundException nfe) {
            return hostResources.getDrawableForDensity(id, density);
        }
    }

    @Nullable
    @Override
    public Drawable getDrawableForDensity(int id, int density, @Nullable Theme theme) {
        if (id == 0) {
            logZeroIdOnce("getDrawableForDensity(theme)");
            return null;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getDrawableForDensity(hostId, density, null);
        try {
            return guestPrimary.getDrawableForDensity(id, density, theme);
        } catch (NotFoundException nfe) {
            return hostResources.getDrawableForDensity(id, density, null);
        }
    }

    @NonNull
    @Override
    public Typeface getFont(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getFont");
            return Typeface.DEFAULT;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getFont(hostId);
        try {
            return guestPrimary.getFont(id);
        } catch (NotFoundException nfe) {
            return hostResources.getFont(id);
        }
    }

    @Override
    public Movie getMovie(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getMovie");
            return null;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getMovie(hostId);
        try {
            return guestPrimary.getMovie(id);
        } catch (NotFoundException nfe) {
            return hostResources.getMovie(id);
        }
    }

    @Override
    public XmlResourceParser getLayout(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getLayout");
            return null;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getLayout(hostId);
        try {
            return guestPrimary.getLayout(id);
        } catch (NotFoundException nfe) {
            return hostResources.getLayout(id);
        }
    }

    @Override
    public XmlResourceParser getAnimation(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getAnimation");
            return null;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getAnimation(hostId);
        try {
            return guestPrimary.getAnimation(id);
        } catch (NotFoundException nfe) {
            return hostResources.getAnimation(id);
        }
    }

    @Override
    public XmlResourceParser getXml(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getXml");
            return null;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.getXml(hostId);
        try {
            return guestPrimary.getXml(id);
        } catch (NotFoundException nfe) {
            return hostResources.getXml(id);
        }
    }

    @NonNull
    @Override
    public TypedArray obtainTypedArray(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("obtainTypedArray");
            return hostResources.obtainAttributes(null, new int[0]);
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.obtainTypedArray(hostId);
        try {
            return guestPrimary.obtainTypedArray(id);
        } catch (NotFoundException nfe) {
            return hostResources.obtainTypedArray(id);
        }
    }

    @Override
    public TypedArray obtainAttributes(AttributeSet set, int[] attrs) {
        return guestPrimary.obtainAttributes(set, attrs);
    }

    @NonNull
    @Override
    public InputStream openRawResource(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("openRawResource");
            return new java.io.ByteArrayInputStream(new byte[0]);
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.openRawResource(hostId);
        try {
            return guestPrimary.openRawResource(id);
        } catch (NotFoundException nfe) {
            return hostResources.openRawResource(id);
        }
    }

    @NonNull
    @Override
    public InputStream openRawResource(int id, TypedValue value) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("openRawResource(value)");
            return new java.io.ByteArrayInputStream(new byte[0]);
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.openRawResource(hostId, value);
        try {
            return guestPrimary.openRawResource(id, value);
        } catch (NotFoundException nfe) {
            return hostResources.openRawResource(id, value);
        }
    }

    @Override
    public AssetFileDescriptor openRawResourceFd(int id) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("openRawResourceFd");
            return null;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) return hostResources.openRawResourceFd(hostId);
        try {
            return guestPrimary.openRawResourceFd(id);
        } catch (NotFoundException nfe) {
            return hostResources.openRawResourceFd(id);
        }
    }

    @Override
    public void getValue(int id, TypedValue outValue, boolean resolveRefs) throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getValue");
            if (outValue != null) outValue.type = TypedValue.TYPE_NULL;
            return;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) {
            hostResources.getValue(hostId, outValue, resolveRefs);
            return;
        }
        try {
            guestPrimary.getValue(id, outValue, resolveRefs);
        } catch (NotFoundException nfe) {
            hostResources.getValue(id, outValue, resolveRefs);
        }
    }

    @Override
    public void getValue(String name, TypedValue outValue, boolean resolveRefs) throws NotFoundException {
        try {
            guestPrimary.getValue(name, outValue, resolveRefs);
        } catch (NotFoundException nfe) {
            hostResources.getValue(name, outValue, resolveRefs);
        }
    }

    @Override
    public void getValueForDensity(int id, int density, TypedValue outValue, boolean resolveRefs)
            throws NotFoundException {
        if (id == 0) {
            logZeroIdOnce("getValueForDensity");
            if (outValue != null) outValue.type = TypedValue.TYPE_NULL;
            return;
        }
        Integer hostId = resolveSyntheticHostId(id);
        if (hostId != null) {
            hostResources.getValueForDensity(hostId, density, outValue, resolveRefs);
            return;
        }
        try {
            guestPrimary.getValueForDensity(id, density, outValue, resolveRefs);
        } catch (NotFoundException nfe) {
            hostResources.getValueForDensity(id, density, outValue, resolveRefs);
        }
    }

    @Override
    public String getResourceName(int resid) throws NotFoundException {
        if (resid == 0) {
            logZeroIdOnce("getResourceName");
            return "";
        }
        Integer hostId = resolveSyntheticHostId(resid);
        if (hostId != null) return hostResources.getResourceName(hostId);
        try {
            return guestPrimary.getResourceName(resid);
        } catch (NotFoundException nfe) {
            return hostResources.getResourceName(resid);
        }
    }

    @Override
    public String getResourcePackageName(int resid) throws NotFoundException {
        if (resid == 0) {
            logZeroIdOnce("getResourcePackageName");
            return guestPackage;
        }
        Integer hostId = resolveSyntheticHostId(resid);
        if (hostId != null) return hostResources.getResourcePackageName(hostId);
        try {
            return guestPrimary.getResourcePackageName(resid);
        } catch (NotFoundException nfe) {
            return hostResources.getResourcePackageName(resid);
        }
    }

    @Override
    public String getResourceTypeName(int resid) throws NotFoundException {
        if (resid == 0) {
            logZeroIdOnce("getResourceTypeName");
            return "";
        }
        Integer hostId = resolveSyntheticHostId(resid);
        if (hostId != null) return hostResources.getResourceTypeName(hostId);
        try {
            return guestPrimary.getResourceTypeName(resid);
        } catch (NotFoundException nfe) {
            return hostResources.getResourceTypeName(resid);
        }
    }

    @Override
    public String getResourceEntryName(int resid) throws NotFoundException {
        if (resid == 0) {
            logZeroIdOnce("getResourceEntryName");
            return "";
        }
        Integer hostId = resolveSyntheticHostId(resid);
        if (hostId != null) return hostResources.getResourceEntryName(hostId);
        try {
            return guestPrimary.getResourceEntryName(resid);
        } catch (NotFoundException nfe) {
            return hostResources.getResourceEntryName(resid);
        }
    }

    @Override
    public DisplayMetrics getDisplayMetrics() {
        return guestPrimary.getDisplayMetrics();
    }

    @Override
    public Configuration getConfiguration() {
        return guestPrimary.getConfiguration();
    }

    @Override
    public void updateConfiguration(Configuration config, DisplayMetrics metrics) {
        super.updateConfiguration(config, metrics);
        if (guestPrimary != null) {
            guestPrimary.updateConfiguration(config, metrics);
        }
    }

    @Override
    public void parseBundleExtras(XmlResourceParser parser, Bundle outBundle)
            throws IOException, XmlPullParserException {
        guestPrimary.parseBundleExtras(parser, outBundle);
    }

    @Override
    public void parseBundleExtra(String tagName, AttributeSet attrs, Bundle outBundle)
            throws XmlPullParserException {
        guestPrimary.parseBundleExtra(tagName, attrs, outBundle);
    }
}
