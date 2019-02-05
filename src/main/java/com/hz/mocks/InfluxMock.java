package com.hz.mocks;

import com.hz.interfaces.InfluxExportInterface;
import com.hz.metrics.Metric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Profile("!influxdb")
@Service
public class InfluxMock implements InfluxExportInterface {

	private static final Logger LOG = LoggerFactory.getLogger(InfluxMock.class);

	@Override
	public void sendMetrics(List<Metric> metricList, Date readTime) {
		LOG.info("Writing stats at {} with {} items", readTime, metricList.size());
	}
}