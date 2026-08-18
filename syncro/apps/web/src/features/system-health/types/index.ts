// Types for Spring Boot Actuator /actuator/health response.
// The endpoint exposes health,info per application.yml.
// With show-details: always, each component includes status + details.

export type ActuatorStatus = "UP" | "DOWN" | "OUT_OF_SERVICE" | "UNKNOWN";

export type ActuatorHealthComponent = {
  status: ActuatorStatus;
  details?: Record<string, unknown>;
};

export type ActuatorHealthResponse = {
  status: ActuatorStatus;
  components?: {
    db?: ActuatorHealthComponent;
    redis?: ActuatorHealthComponent;
    mqtt?: ActuatorHealthComponent;
    influxdb?: ActuatorHealthComponent;
    diskSpace?: ActuatorHealthComponent;
    ping?: ActuatorHealthComponent;
    [key: string]: ActuatorHealthComponent | undefined;
  };
};
