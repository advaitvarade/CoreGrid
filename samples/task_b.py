import time
from pathlib import Path
print('Job B started', flush=True)
time.sleep(4)
Path('/job/output/result_b.txt').write_text('Job B finished successfully\n')
print('Job B finished', flush=True)
