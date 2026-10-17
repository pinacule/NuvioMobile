FROM python:3.12-slim

WORKDIR /srv/gateway

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

ENV PORT=8080
EXPOSE 8080

CMD ["python", "gateway.py"]
