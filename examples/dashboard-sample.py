"""Sample Phase 5 upload: writes a CSV to CoreGrid's output directory."""
import csv
from pathlib import Path

output = Path('/job/output/squares.csv')
with output.open('w', newline='') as f:
    writer = csv.writer(f)
    writer.writerow(['number', 'square'])
    for number in range(1, 11):
        writer.writerow([number, number * number])
print(f'Generated: {output}')
