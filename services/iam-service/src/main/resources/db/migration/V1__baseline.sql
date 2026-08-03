CREATE TABLE service_metadata (
    id BIGINT NOT NULL AUTO_INCREMENT,
    service_name VARCHAR(64) NOT NULL,
    initialized_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_service_metadata_name (service_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO service_metadata (service_name) VALUES ('iam-service');
