import mysql.connector
import random
from datetime import datetime, timedelta

# MySQL connection details (adjust if needed)
HOST = 'localhost'
USER = 'user'
PASSWORD = 'password'
DATABASE = 'testdb'
TABLE = 'sensor_data'

def create_table():
    conn = mysql.connector.connect(host=HOST, user=USER, password=PASSWORD, database=DATABASE)
    cursor = conn.cursor()
    cursor.execute(f"""
        CREATE TABLE IF NOT EXISTS {TABLE} (
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            device_id VARCHAR(50),
            timestamp TIMESTAMP,
            temperature DOUBLE,
            humidity DOUBLE
        );
    """)
    conn.commit()
    cursor.close()
    conn.close()
    print(f"Table {TABLE} created or already exists.")

def generate_data(num_rows=100000):
    conn = mysql.connector.connect(host=HOST, user=USER, password=PASSWORD, database=DATABASE)
    cursor = conn.cursor()
    
    # Generate dummy data
    base_time = datetime.now() - timedelta(days=30)
    devices = [f"device_{i:03d}" for i in range(1, 101)]  # 100 devices
    
    batch_size = 1000
    for i in range(0, num_rows, batch_size):
        batch = []
        for j in range(batch_size):
            if i + j >= num_rows:
                break
            device_id = random.choice(devices)
            ts = base_time + timedelta(seconds=random.randint(0, 30*24*3600))  # Random time in last 30 days
            temp = round(random.uniform(20.0, 30.0), 2)
            hum = round(random.uniform(40.0, 60.0), 2)
            batch.append((device_id, ts, temp, hum))
        
        cursor.executemany(f"INSERT INTO {TABLE} (device_id, timestamp, temperature, humidity) VALUES (%s, %s, %s, %s)", batch)
        conn.commit()
        print(f"Inserted {min(batch_size, len(batch))} rows (total: {i + len(batch)})")
    
    cursor.close()
    conn.close()
    print(f"Generated {num_rows} dummy rows in {TABLE}.")

if __name__ == "__main__":
    create_table()
    generate_data(15000000)  # Adjust number of rows here