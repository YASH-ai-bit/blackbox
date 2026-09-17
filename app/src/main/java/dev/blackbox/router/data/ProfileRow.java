package dev.blackbox.router.data;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName="profiles")
public class ProfileRow {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public byte[] payload = new byte[0];
    public long updated;
}
