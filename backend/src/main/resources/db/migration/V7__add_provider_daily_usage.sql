CREATE TABLE provider_daily_usage (
    provider VARCHAR(50) NOT NULL,
    day      DATE        NOT NULL,
    calls    INTEGER     NOT NULL CHECK (calls > 0),
    PRIMARY KEY (provider, day)
);
