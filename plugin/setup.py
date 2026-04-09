from setuptools import setup, find_packages

setup(
    name="hermes-android",
    version="0.3.0",
    packages=find_packages(),
    install_requires=[
        "requests>=2.28.0",
        "aiohttp>=3.9.0",
        "segno>=1.6.0",
    ],
    package_data={
        "": ["skills/android/*.md"],
    },
    python_requires=">=3.11",
)
