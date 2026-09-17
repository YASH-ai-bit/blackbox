package dev.blackbox.router.data;
import androidx.room.*;
import java.util.List;
@Dao public interface ClientDao {
    @Query("SELECT * FROM clients WHERE profileId=:profile ORDER BY updated DESC LIMIT 128") List<ClientRow> all(String profile);
    @Insert(onConflict=OnConflictStrategy.REPLACE) void save(ClientRow row);
    @Query("DELETE FROM clients WHERE profileId=:profile") void delete(String profile);
}
