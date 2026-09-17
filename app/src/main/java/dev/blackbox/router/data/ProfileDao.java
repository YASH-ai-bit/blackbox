package dev.blackbox.router.data;
import androidx.room.*;
import java.util.List;
@Dao public interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY updated DESC") List<ProfileRow> all();
    @Insert(onConflict=OnConflictStrategy.REPLACE) void save(ProfileRow row);
    @Query("DELETE FROM profiles WHERE id=:id") void delete(String id);
}
