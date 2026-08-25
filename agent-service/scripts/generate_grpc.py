"""从仓库根契约生成 Python Protobuf/gRPC 代码。"""

from pathlib import Path

from grpc_tools import protoc

AGENT_SERVICE_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = AGENT_SERVICE_ROOT.parent
PROTO_ROOT = REPOSITORY_ROOT / "contracts" / "agent" / "v1"
PROTO_FILE = PROTO_ROOT / "agent_runtime.proto"
OUTPUT_ROOT = (
    AGENT_SERVICE_ROOT / "src" / "devpilot_agent_service" / "rpc" / "generated"
)


def main() -> int:
    """执行可重复 codegen，并把 grpcio-tools 的顶层 import 固定为包内相对 import。"""

    if not PROTO_FILE.is_file():
        raise FileNotFoundError(f"shared proto not found: {PROTO_FILE}")
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    result = protoc.main(
        [
            "grpc_tools.protoc",
            f"--proto_path={PROTO_ROOT}",
            f"--python_out={OUTPUT_ROOT}",
            f"--grpc_python_out={OUTPUT_ROOT}",
            str(PROTO_FILE),
        ]
    )
    if result != 0:
        raise RuntimeError(f"grpc_tools.protoc failed with exit code {result}")

    grpc_output = OUTPUT_ROOT / "agent_runtime_pb2_grpc.py"
    generated = grpc_output.read_text(encoding="utf-8")
    absolute_import = "import agent_runtime_pb2 as agent__runtime__pb2"
    relative_import = "from . import agent_runtime_pb2 as agent__runtime__pb2"
    if absolute_import not in generated:
        raise RuntimeError("generated grpc module import layout changed")
    grpc_output.write_text(
        generated.replace(absolute_import, relative_import, 1),
        encoding="utf-8",
        newline="\n",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
