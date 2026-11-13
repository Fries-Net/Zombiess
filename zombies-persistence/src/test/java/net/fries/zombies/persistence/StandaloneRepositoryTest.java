package net.fries.zombies.persistence;

import net.fries.zombies.common.config.DatabaseSettings;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class StandaloneRepositoryTest extends RepositoryContract {
    @TempDir Path directory;
    @Override DatabaseSettings settings() {
        return new DatabaseSettings(DatabaseSettings.Mode.STANDALONE,
                "jdbc:h2:file:" + directory.resolve("accounts").toAbsolutePath().toString().replace('\\', '/')
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "", 4, 2, 256, 5, true);
    }
}
