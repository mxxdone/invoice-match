FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY pyproject.toml requirements.txt README.md ./
COPY src ./src
RUN pip install --no-cache-dir --disable-pip-version-check . \
    && useradd --uid 10001 --create-home worker
USER worker
CMD ["ai-worker", "consume"]
