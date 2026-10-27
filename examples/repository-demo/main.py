from pathlib import Path
from helpers import square
import csv

result = Path("/job/output/squares.csv")
result.parent.mkdir(parents=True, exist_ok=True)
with result.open("w", newline="") as f:
    writer = csv.writer(f)
    writer.writerow(["number", "square"])
    for value in range(1, 11):
        writer.writerow([value, square(value)])
print("Multifile GitHub project finished:", result)
