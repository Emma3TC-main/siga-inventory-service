package utp.siga.inventory.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import utp.siga.inventory.application.dto.LocationType;
import utp.siga.inventory.application.dto.LocationView;
import utp.siga.inventory.application.port.out.LocationQueryPort;

@Repository
public class PostgresLocationQuery implements LocationQueryPort {

    private static final String FIND_ACTIVE = """
            SELECT id, code, name, location_type, parent_id, active, version
            FROM inventory.location
            WHERE active = true
            ORDER BY code ASC, id ASC
            """;

    private final JdbcTemplate jdbc;

    public PostgresLocationQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<LocationView> findActiveLocations() {
        return jdbc.query(FIND_ACTIVE, this::mapRow);
    }

    private LocationView mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new LocationView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("code"),
                resultSet.getString("name"),
                LocationType.valueOf(resultSet.getString("location_type")),
                resultSet.getObject("parent_id", UUID.class),
                resultSet.getBoolean("active"),
                resultSet.getLong("version"));
    }
}
