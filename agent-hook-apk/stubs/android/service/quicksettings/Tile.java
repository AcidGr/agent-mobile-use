package android.service.quicksettings;

import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;

public final class Tile implements Parcelable {
    public static final int STATE_UNAVAILABLE = 0;
    public static final int STATE_INACTIVE = 1;
    public static final int STATE_ACTIVE = 2;

    public static final Parcelable.Creator<Tile> CREATOR = new Parcelable.Creator<Tile>() {
        @Override
        public Tile createFromParcel(Parcel source) { return new Tile(); }
        @Override
        public Tile[] newArray(int size) { return new Tile[size]; }
    };

    public int getState() { return 0; }
    public void setState(int state) {}
    public Icon getIcon() { return null; }
    public void setIcon(Icon icon) {}
    public CharSequence getLabel() { return null; }
    public void setLabel(CharSequence label) {}
    public CharSequence getSubtitle() { return null; }
    public void setSubtitle(CharSequence subtitle) {}
    public CharSequence getContentDescription() { return null; }
    public void setContentDescription(CharSequence contentDescription) {}
    public void updateTile() {}
    @Override
    public int describeContents() { return 0; }
    @Override
    public void writeToParcel(Parcel dest, int flags) {}
}
