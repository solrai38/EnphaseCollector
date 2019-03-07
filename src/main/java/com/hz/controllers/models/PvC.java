package com.hz.controllers.models;

import com.hz.models.database.Event;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class PvC {
	List<Value> production = new ArrayList<>();
	List<Value> consumption = new ArrayList<>();

	public void addEvent(Event event) {
		consumption.add(new Value(event.getTime(), event.getConsumption().multiply(BigDecimal.valueOf(-1))));
		production.add(new Value(event.getTime(), event.getProduction()));
	}
}