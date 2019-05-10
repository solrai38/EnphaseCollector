package com.hz.services;

import com.hz.configuration.EnphaseCollectorProperties;
import com.hz.configuration.PvOutputClientConfig;
import com.hz.interfaces.PvOutputExportInterface;
import com.hz.metrics.Metric;
import com.hz.utils.Convertors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
@Profile("pvoutput")
public class PvOutputService implements PvOutputExportInterface {
	private static final Logger LOG = LoggerFactory.getLogger(PvOutputService.class);

	private BigDecimal energyGeneratedAccumulator = BigDecimal.ZERO;
	private BigDecimal energyConsumedAccumulator = BigDecimal.ZERO;
	private int powerGenerated = 0;
	private int powerConsumed = 0;
	private LocalDateTime nextUpdate = LocalDateTime.now().toLocalDate().atStartOfDay();

	private final EnphaseCollectorProperties properties;
	private final RestTemplate pvRestTemplate;

	private DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
	private DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");
	private static final int INTERVAL = 5;

	private static final int UPDATE_DATE = 0;
	private static final int UPDATE_TIME = 1;
	private static final int GENERATED_TOTAL = 2;
	private static final int CONSUMED_TOTAL = 4;

	// TODO allow for 5,10,15 min intervals
	// pvoutput has a min of 5 minutes

	@Autowired
	public PvOutputService(EnphaseCollectorProperties properties, RestTemplate pvRestTemplate) {
		this.properties = properties;
		this.pvRestTemplate = pvRestTemplate;

		// Roll forward nextUpdate to nearest 5 min in future
		LocalDateTime now = LocalDateTime.now();
		while (now.isAfter(nextUpdate)) {
			nextUpdate = nextUpdate.plusMinutes(INTERVAL);
		}
	}

	@PostConstruct
	private void initAccumulatorsFromPvOutput() {
		try {
			String results = this.pvRestTemplate.getForObject(properties.getPvOutputResource().getUrl() + PvOutputClientConfig.GET_STATUS, String.class);
			String[] elements = results.split(",");
			LocalDate lastUpdateDate = LocalDate.parse(elements[UPDATE_DATE], DateTimeFormatter.ofPattern("yyyyMMdd"));
			LocalTime lastUpdateTime = LocalTime.parse(elements[UPDATE_TIME], DateTimeFormatter.ISO_LOCAL_TIME);

			LOG.info("PvOutput was last updated at {} {}", lastUpdateDate, lastUpdateTime);
			if (lastUpdateDate.compareTo(LocalDate.now()) == 0) {
				// Today so we can set accumulators
				this.energyGeneratedAccumulator = BigDecimal.valueOf(Integer.parseInt(elements[GENERATED_TOTAL]));
				this.energyConsumedAccumulator = BigDecimal.valueOf(Integer.parseInt(elements[CONSUMED_TOTAL]));

				LOG.warn("Setting Accumulators to G:{} and C:{} some updates may be missing.  Next update will be {}", this.energyGeneratedAccumulator, this.energyConsumedAccumulator, nextUpdate);
			}
		} catch (HttpClientErrorException e) {
			LOG.error("Error reading PvOutput Status: {} {}", e.getMessage(), e.getResponseBodyAsString());
		} catch (Exception e) {
			LOG.error("Error parsing PvOutput Status: {} {}", e.getMessage(), e);
		}
	}

	@Override
	public void sendMetrics(List<Metric> metrics, LocalDateTime readTime) {
		BigDecimal production = getMetric(metrics, "solar.production.current").map(metric -> BigDecimal.valueOf(metric.getValue())).orElse(BigDecimal.ZERO);
		BigDecimal consumption = getMetric(metrics, "solar.consumption.current").map(metric -> BigDecimal.valueOf(metric.getValue())).orElse(BigDecimal.ZERO);
		BigDecimal voltage = getMetric(metrics, "solar.production.voltage").map(metric -> BigDecimal.valueOf(metric.getValue())).orElse(BigDecimal.ZERO);

		this.updateAccumulators(Convertors.convertToWattHours(production,properties.getRefreshAsMinutes()), Convertors.convertToWattHours(consumption, properties.getRefreshAsMinutes()));
		this.updatePower(production.intValue(), consumption.intValue());

		if (readTime.isAfter(nextUpdate)) {
			LOG.info("dt={} v1={} v2={} v3={} v4={} v6={}", nextUpdate, energyGeneratedAccumulator, powerGenerated, energyConsumedAccumulator, powerConsumed, voltage);

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

			MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
			map.add("d", dateFormatter.format(nextUpdate));
			map.add("t", timeFormatter.format(nextUpdate));
			map.add("v1", energyGeneratedAccumulator.toString());
			map.add("v2", String.valueOf(powerGenerated));
			map.add("v3", energyConsumedAccumulator.toString());
			map.add("v4", String.valueOf(powerConsumed));
			map.add("v6", String.valueOf(voltage));

			HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(map, headers);

			try {
				final ResponseEntity<String> stringResponseEntity = this.pvRestTemplate.postForEntity(properties.getPvOutputResource().getUrl() + PvOutputClientConfig.ADD_STATUS, requestEntity, String.class);
				if (stringResponseEntity.getStatusCodeValue() != 200) {
					LOG.error("Error updating PvOutput: Request {} -> {}", requestEntity.getBody(), stringResponseEntity.hasBody() ? stringResponseEntity.getBody() : "NO BODY");		// NOSONAR
				}
			} catch (HttpClientErrorException e) {
				LOG.error("Error updating PvOutput: {} {}", e.getMessage(), e.getResponseBodyAsString());
			}

			int day = nextUpdate.getDayOfMonth();
			nextUpdate = nextUpdate.plusMinutes(5);
			if (day != nextUpdate.getDayOfMonth()) {
				clearAccumulators();
			}
			clearPower();
		}
	}

	private Optional<Metric> getMetric(List<Metric> metrics, String name) {
		return metrics.stream().filter(metric -> metric.getName().equalsIgnoreCase(name)).findFirst();
	}

	private void clearAccumulators() {
		this.energyConsumedAccumulator = BigDecimal.ZERO;
		this.energyGeneratedAccumulator = BigDecimal.ZERO;
		this.powerConsumed = 0;
		this.powerGenerated = 0;
	}

	private void clearPower() {
		this.powerGenerated = 0;
		this.powerConsumed = 0;
	}

	private void updatePower(int powerGenerated, int powerConsumed) {
		if (powerGenerated > this.powerGenerated) {
			this.powerGenerated = powerGenerated;
		}
		if (powerConsumed > this.powerConsumed) {
			this.powerConsumed = powerConsumed;
		}
	}

	private void updateAccumulators(BigDecimal energyGenerated, BigDecimal energyConsumed) {
		this.energyGeneratedAccumulator = this.energyGeneratedAccumulator.add(energyGenerated);
		this.energyConsumedAccumulator = this.energyConsumedAccumulator.add(energyConsumed);
	}
}
