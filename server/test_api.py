import requests

url = "http://127.0.0.1:5000/resolve"
data = {"url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ"}

try:
    response = requests.post(url, json=data)
    print(f"Status Code: {response.status_code}")
    print(f"Response: {response.json()}")
except Exception as e:
    print(f"Error: {e}")
