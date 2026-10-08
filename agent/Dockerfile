FROM python:3.12-slim
WORKDIR /app
# local embeds on CPU inside the container; gemini keeps the image small.
ARG EMBEDDING_PROVIDER=gemini
ARG EMBEDDING_MODEL=BAAI/bge-base-en-v1.5
ENV HF_HOME=/opt/hf
COPY requirements.txt requirements-local.txt ./
RUN pip install --no-cache-dir -r requirements.txt
RUN if [ "$EMBEDDING_PROVIDER" = "local" ]; then \
      pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
      && pip install --no-cache-dir -r requirements-local.txt \
      && python -c "import sys; from sentence_transformers import SentenceTransformer; SentenceTransformer(sys.argv[1])" "$EMBEDDING_MODEL"; \
    fi \
    && mkdir -p /opt/hf && chown -R 10001:10001 /opt/hf
# The model is baked in at build time; change EMBEDDING_MODEL by rebuilding, not by downloading at runtime.
ENV HF_HUB_OFFLINE=1
COPY app ./app
RUN mkdir -p /data && chown 10001:10001 /data
USER 10001
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
