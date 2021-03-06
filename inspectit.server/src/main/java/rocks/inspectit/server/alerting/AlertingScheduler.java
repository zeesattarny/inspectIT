package rocks.inspectit.server.alerting;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import rocks.inspectit.server.alerting.state.AlertingState;
import rocks.inspectit.server.ci.event.AbstractAlertingDefinitionEvent;
import rocks.inspectit.shared.all.cmr.property.spring.PropertyUpdate;
import rocks.inspectit.shared.all.spring.logger.Log;
import rocks.inspectit.shared.cs.ci.AlertingDefinition;
import rocks.inspectit.shared.cs.communication.data.cmr.AlertClosingReason;

/**
 * Scheduler for the {@link ThresholdChecker} to check the threshold specified by the
 * {@link AlertingDefinition}.
 *
 * @author Marius Oehler
 *
 */
@Component
public class AlertingScheduler implements Runnable, ApplicationListener<AbstractAlertingDefinitionEvent> {

	/**
	 * The execution interval in minutes of this runnable.
	 */
	static final long CHECK_INTERVAL = 1L;

	/**
	 * Logger for the class.
	 */
	@Log
	Logger log;

	/**
	 * Activation state of this service.
	 */
	@Value("${alerting.active}")
	boolean active;

	/**
	 * {@link ExecutorService} instance.
	 */
	@Autowired
	@Resource(name = "scheduledExecutorService")
	ScheduledExecutorService executorService;

	/**
	 * {@link ThresholdChecker} instance.
	 */
	@Autowired
	ThresholdChecker thresholdChecker;

	/**
	 * {@link ScheduledFuture} of the currently executed {@link AlertingScheduler}.
	 */
	private ScheduledFuture<?> scheduledFuture;

	/**
	 * The currently {@link AlertingState}s holding the existing {@link AlertingDefinition}s..
	 */
	private List<AlertingState> alertingStates = new CopyOnWriteArrayList<>();

	/**
	 * Updates the state of this {@link AlertingDefinition} instance. It is getting enabled or
	 * disabled according to the {@link #active} field.
	 */
	@PostConstruct
	@PropertyUpdate(properties = { "alerting.active" })
	public void updateState() {
		if (active) {
			if ((scheduledFuture == null) || scheduledFuture.isDone()) {
				scheduledFuture = executorService.scheduleAtFixedRate(this, 0L, CHECK_INTERVAL, TimeUnit.MINUTES);
			}
			if (log.isInfoEnabled()) {
				log.info("|-Alerting scheduler has been started..");
			}
		} else {
			if ((scheduledFuture != null) && !scheduledFuture.isDone()) {
				scheduledFuture.cancel(false);
			}
			if (log.isInfoEnabled()) {
				log.info("|-Alerting scheduler has been stopped..");
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void run() {
		if (log.isDebugEnabled()) {
			log.debug("|-Checking alert definitions...");
		}

		long currentTime = System.currentTimeMillis();

		for (AlertingState alertingState : alertingStates) {
			try {
				long nextCheckTime = alertingState.getLastCheckTime() + alertingState.getAlertingDefinition().getTimeRange(TimeUnit.MILLISECONDS);

				if (nextCheckTime <= currentTime) {
					thresholdChecker.checkThreshold(alertingState);
				}
			} catch (Exception e) {
				if (log.isErrorEnabled()) {
					log.error("Unexpected exception occured.", e);
				}
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onApplicationEvent(AbstractAlertingDefinitionEvent event) {
		if (event == null) {
			return;
		}

		switch (event.getType()) {
		case LOADED:
			loadedAlertingDefinitions(event);
			break;
		case ADDED:
			createdAlertingDefinition(event);
			break;
		case REMOVED:
			deletedAlertingDefinition(event);
			break;
		case UPDATE:
			updatedAlertingDefinition(event);
			break;

		default:
			break;
		}
	}

	/**
	 * Handle the {@link AbstractAlertingDefinitionEvent}.
	 *
	 * @param event
	 *            the received {@link AbstractAlertingDefinitionEvent}
	 */
	private void loadedAlertingDefinitions(AbstractAlertingDefinitionEvent event) {
		alertingStates.clear();

		for (AlertingDefinition definition : event.getAlertingDefinitions()) {
			alertingStates.add(new AlertingState(definition));
		}
	}

	/**
	 * Handle the {@link AbstractAlertingDefinitionEvent}.
	 *
	 * @param event
	 *            the received {@link AbstractAlertingDefinitionEvent}
	 */
	private void createdAlertingDefinition(AbstractAlertingDefinitionEvent event) {
		alertingStates.add(new AlertingState(event.getFirst()));
	}

	/**
	 * Handle the {@link AbstractAlertingDefinitionEvent}.
	 *
	 * @param event
	 *            the received {@link AbstractAlertingDefinitionEvent}
	 */
	private void deletedAlertingDefinition(AbstractAlertingDefinitionEvent event) {
		for (AlertingState state : alertingStates) {
			if (Objects.equals(state.getAlertingDefinition().getId(), event.getFirst().getId())) {
				if (null != state.getAlert()) {
					state.getAlert().close(System.currentTimeMillis(), AlertClosingReason.ALERTING_DEFINITION_DELETED);
				}
				alertingStates.remove(state);
				break;
			}
		}
	}

	/**
	 * Handle the {@link AbstractAlertingDefinitionEvent}.
	 *
	 * @param event
	 *            the received {@link AbstractAlertingDefinitionEvent}
	 */
	private void updatedAlertingDefinition(AbstractAlertingDefinitionEvent event) {
		Iterator<AlertingState> iterator = alertingStates.iterator();

		while (iterator.hasNext()) {
			AlertingState state = iterator.next();

			if (Objects.equals(state.getAlertingDefinition().getId(), event.getFirst().getId())) {
				state.setAlertingDefinition(event.getFirst());
				if (null != state.getAlert()) {
					state.getAlert().setAlertingDefinition(event.getFirst());
				}
				break;
			}
		}
	}
}
