package dev.blackbox.router.data;
import androidx.room.*;
@Database(entities={ProfileRow.class,ClientRow.class},version=2,exportSchema=false)
public abstract class BlackboxDatabase extends RoomDatabase { public abstract ProfileDao profiles(); public abstract ClientDao clients(); }
