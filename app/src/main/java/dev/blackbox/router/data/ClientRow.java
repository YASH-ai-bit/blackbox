package dev.blackbox.router.data;
import androidx.room.*;
import androidx.annotation.NonNull;
@Entity(tableName="clients",primaryKeys={"profileId","mac"})
public class ClientRow {
    @NonNull public String profileId="";
    @NonNull public String mac="";
    @NonNull public byte[] payload=new byte[0];
    public long updated;
}
