import mysql.connector
import random
from datetime import datetime, timedelta
import yaml
import os
import re

import argparse

parser = argparse.ArgumentParser()
parser.add_argument('--config', default=os.path.join(os.path.dirname(__file__), '..', 'config', 'config.yaml'))
parser.add_argument('--table', required=True)
parser.add_argument('--num-rows', type=int, default=10000)
args = parser.parse_args()

# Load config
with open(args.config, 'r') as f:
    config = yaml.safe_load(f)

# Get connections
mysql_conn = config['connections']['mysql']
HOST = 'localhost'  # Override for host machine connection
USER = mysql_conn['user']
PASSWORD = mysql_conn['password']
DATABASE = mysql_conn['database']
TABLE = args.table

# Get table schema
table_config = next((t for t in config['mysql']['tables'] if t['name'] == TABLE), None)
if not table_config:
    raise ValueError(f"Table {TABLE} not found in schema config")

def generate_value(col_type):
    col_type = col_type.upper()
    if 'BIGINT' in col_type:
        return random.randint(1, 100000)
    elif 'INT' in col_type:
        return random.randint(1, 100)
    elif 'VARCHAR' in col_type or 'TEXT' in col_type:
        match = re.search(r'\((\d+)\)', col_type)
        length = int(match.group(1)) if match else 50
        return ''.join(random.choices('abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789', k=min(length, 10)))
    elif 'TIMESTAMP' in col_type or 'DATETIME' in col_type:
        base_time = datetime.now() - timedelta(days=30)
        return base_time + timedelta(seconds=random.randint(0, 30*24*3600))
    elif 'FLOAT' in col_type or 'DOUBLE' in col_type:
        return round(random.uniform(0.0, 100.0), 2)
    elif 'DECIMAL' in col_type:
        match = re.search(r'DECIMAL\((\d+),(\d+)\)', col_type)
        if match:
            precision = int(match.group(1))
            scale = int(match.group(2))
            max_val = 10**(precision - scale) - 1
            return round(random.uniform(0, max_val), scale)
        return round(random.uniform(0.0, 100.0), 2)
    else:
        return "default_value"  # fallback

def create_table():
    conn = mysql.connector.connect(host=HOST, user=USER, password=PASSWORD, database=DATABASE)
    cursor = conn.cursor()

    columns_sql = []
    for col in table_config['columns']:
        col_sql = f"{col['name']} {col['type']}"
        if col.get('primary'):
            col_sql += " PRIMARY KEY"
        if col.get('auto_increment'):
            col_sql += " AUTO_INCREMENT"
        columns_sql.append(col_sql)

    create_sql = f"CREATE TABLE IF NOT EXISTS {TABLE} ({', '.join(columns_sql)});"
    cursor.execute(create_sql)
    conn.commit()
    cursor.close()
    conn.close()
    print(f"Table {TABLE} created or already exists.")

def generate_data(num_rows=100000):
    conn = mysql.connector.connect(host=HOST, user=USER, password=PASSWORD, database=DATABASE)
    cursor = conn.cursor()

    # Get columns for insert (exclude auto_increment primary key)
    insert_columns = [col['name'] for col in table_config['columns'] if not col.get('auto_increment')]
    placeholders = ', '.join(['%s'] * len(insert_columns))
    insert_sql = f"INSERT INTO {TABLE} ({', '.join(insert_columns)}) VALUES ({placeholders})"

    batch_size = 1000
    for i in range(0, num_rows, batch_size):
        batch = []
        for j in range(batch_size):
            if i + j >= num_rows:
                break
            values = [generate_value(col['type']) for col in table_config['columns'] if not col.get('auto_increment')]
            batch.append(tuple(values))

        cursor.executemany(insert_sql, batch)
        conn.commit()
        print(f"Inserted {min(batch_size, len(batch))} rows (total: {i + len(batch)})")

    cursor.close()
    conn.close()
    print(f"Generated {num_rows} dummy rows in {TABLE}.")

if __name__ == "__main__":
    create_table()
    generate_data(args.num_rows)