# for dev purposes only
FROM kestra/kestra:develop

COPY build/libs/* /app/plugins/
