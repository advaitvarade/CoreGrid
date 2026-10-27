import torch
from pathlib import Path

assert torch.cuda.is_available(), "CUDA unavailable"
print("GPU:", torch.cuda.get_device_name(0))
print("PyTorch:", torch.__version__)
a = torch.randn(2000, 2000, device="cuda")
b = a @ a
torch.cuda.synchronize()
print("Result device:", b.device)
Path("/job/output").mkdir(exist_ok=True)
torch.save(b.cpu(), "/job/output/result.pt")
print("GPU computation successful!")
