# FusionSolar Data Fetcher

A command-line tool to download historical energy production and consumption data from the Huawei FusionSolar API and
export it to CSV format.

This tool uses the reverse-engineered FusionSolar "end customer" API, which only requires your regular login
credentials. Unlike the official API, no API token is needed, which is typically only available through installers.

The data is typically available in 5-minute intervals from the FusionSolar API. The tool fetches all available data
points for the specified date range.

## Requirements

- Java 25 or higher (JRE or JDK)
- Valid FusionSolar account credentials
- Station ID and Battery Device ID from your FusionSolar installation

## Usage

### Download Pre-built JAR

Download the latest `fusionsolar-data-fetcher-<version>.jar` from
the [GitHub Releases](https://github.com/Skyleiger/fusionsolar-data-fetcher/releases) page.

### Basic Usage

```bash
java -jar fusionsolar-data-fetcher.jar \
  --username "your-username" \
  --password "your-password" \
  --subdomain "region01eu5" \
  --station-id "NE=12345678" \
  --battery-id "NE=12345678.BATTERY.1" \
  --start-date "2024-01-01" \
  --end-date "2026-01-31" \
  --target-file "output.csv"
```

### With Session Persistence

To avoid repeated authentication, use the `--session-file` option:

```bash
java -jar fusionsolar-data-fetcher.jar \
  --username "your-username" \
  --password "your-password" \
  --subdomain "region01eu5" \
  --station-id "NE=12345678" \
  --battery-id "NE=12345678.BATTERY.1" \
  --start-date "2024-01-01" \
  --target-file "output.csv" \
  --session-file "session.json"
```

### Parameters

| Parameter        | Required | Description                                              |
|------------------|----------|----------------------------------------------------------|
| `--username`     | Yes      | Your FusionSolar username                                |
| `--password`     | Yes      | Your FusionSolar password                                |
| `--subdomain`    | Yes      | FusionSolar subdomain (e.g., `region01eu5`, `uni001eu5`) |
| `--station-id`   | Yes      | Your station ID from FusionSolar                         |
| `--battery-id`   | Yes      | Your battery device ID from FusionSolar                  |
| `--start-date`   | Yes      | Start date in format `YYYY-MM-DD`                        |
| `--end-date`     | No       | End date in format `YYYY-MM-DD` (defaults to today)      |
| `--target-file`  | Yes      | Path to the output CSV file                              |
| `--session-file` | No       | Path to session file for authentication persistence      |

## Output Format

The CSV output contains the following columns:

| Column                     | Description                                                       |
|----------------------------|-------------------------------------------------------------------|
| `utc_timestamp`            | Timestamp in UTC                                                  |
| `europe_berlin_timestamp`  | Timestamp in Europe/Berlin timezone                               |
| `pv_power`                 | PV power in kW                                                    |
| `use_power`                | Power consumption in kW                                           |
| `pv_use_power`             | Direct PV usage in kW                                             |
| `battery_power`            | Battery power in kW (positive = charging, negative = discharging) |
| `battery_soc`              | Battery state of charge in %                                      |
| `total_pv_energy`          | Total PV energy for the day in kWh                                |
| `total_use_energy`         | Total energy consumption for the day in kWh                       |
| `total_self_use_energy`    | Total self-consumed energy for the day in kWh                     |
| `total_grid_import_energy` | Total grid import energy for the day in kWh                       |
| `total_grid_export_energy` | Total grid export energy for the day in kWh                       |

## Finding Your Station and Battery IDs

1. Log in to your FusionSolar account
2. Navigate to your station/plant
3. Open your browser's Developer Tools (F12)
4. Go to the Network tab
5. Navigate through the FusionSolar interface
6. Look for API calls containing `stationDn` and `deviceDn` parameters
7. These contain your Station ID and Battery Device ID

## Error Handling

- If fetching data for a specific date fails, the application will abort immediately
- Previously written data in the CSV file will be preserved
- Detailed error messages are logged to help diagnose issues

## Building from Source

For development or customization, you can build the project using Gradle:

```bash
./gradlew build
```

The JAR file will be created in `build/libs/`

**Requirements for building:**

- Java 25 or higher (JDK)
- Gradle (included via wrapper)

## Acknowledgments

This project was inspired by [FusionSolarPy](https://github.com/jgriss/FusionSolarPy), which originally
reverse-engineered the unofficial FusionSolar API endpoints. Special thanks to the FusionSolarPy project for documenting
the API and making it accessible to the community.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Disclaimer

This tool is not officially affiliated with or endorsed by Huawei. Use at your own risk.
